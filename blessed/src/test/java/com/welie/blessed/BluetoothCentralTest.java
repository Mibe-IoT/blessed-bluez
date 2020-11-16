package com.welie.blessed;

import com.welie.blessed.bluez.BluezAdapter;
import com.welie.blessed.bluez.BluezDevice;
import org.bluez.exceptions.*;
import org.freedesktop.dbus.DBusMap;
import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.ObjectManager;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.types.UInt16;
import org.freedesktop.dbus.types.Variant;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static com.welie.blessed.BluetoothCentral.*;
import static com.welie.blessed.BluetoothPeripheral.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// TODO
// - Test proving that we always get the same BluetoothPeripheral object for a device while scanning or doing getPeripheral
// - Test proving that we always get the same ScanResult object while scanning for a device
// - Test proving that we continue scanning after connecting to a device that was autoConnected and there are more
// - Test proving that we don't call onDiscoveredPeripheral anymore after stopScan is called although signals are coming in
// - Test proving that we handle connectionFailed correctly
// - Test proving that we don't call startDiscovery if we are already discovering
// - Test proving that we don't call stopDiscovery if we are not discovering

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class BluetoothCentralTest {
    @Mock
    DBusConnection dBusConnection;

    @Mock
    BluetoothCentralCallback callback;

    @Mock
    BluezAdapter bluezAdapter;

    @Mock
    BluezDevice bluezDevice;

    @Mock
    BluezDevice bluezDeviceHts;

    @Mock
    BluetoothPeripheralCallback peripheralCallback;

    private static final UUID BLP_SERVICE_UUID = UUID.fromString("00001810-0000-1000-8000-00805f9b34fb");
    private static final String DUMMY_MAC_ADDRESS_BLP = "12:34:56:65:43:21";
    private static final String DUMMY_MAC_ADDRESS_PATH_BLP = "/org/bluez/hci0/dev_" + DUMMY_MAC_ADDRESS_BLP.replace(":", "_");
    private static final String DUMMY_PERIPHERAL_NAME_BLP = "Beurer BM57";

    private static final UUID HTS_SERVICE_UUID = UUID.fromString("00001809-0000-1000-8000-00805f9b34fb");
    private static final String DUMMY_MAC_ADDRESS_HTS = "44:33:22:11:99:77";
    private static final String DUMMY_MAC_ADDRESS_PATH_HTS = "/org/bluez/hci0/dev_" + DUMMY_MAC_ADDRESS_HTS.replace(":", "_");
    private static final String DUMMY_PERIPHERAL_NAME_HTS = "Taidoc 1241";

    @BeforeEach
    void setup() {
    }

    @Test
    void Constructor_may_not_be_null() {
        Assertions.assertThrows(NullPointerException.class, () -> {
            BluetoothCentral central = new BluetoothCentral(null, null, null);
        });
        Assertions.assertThrows(NullPointerException.class, () -> {
            BluetoothCentral central = new BluetoothCentral(callback, null, null);
        });
        Assertions.assertThrows(NullPointerException.class, () -> {
            BluetoothCentral central = new BluetoothCentral(callback, Collections.emptySet(), null);
        });
    }

    @Test
    void When_creating_central_while_adapter_is_off_then_turn_it_on() throws InterruptedException {
        // Given
        when(bluezAdapter.isPowered()).thenReturn(false);

        // When
        BluetoothCentral central = new BluetoothCentral(callback, Collections.emptySet(), bluezAdapter);

        await().until(() -> central.commandQueue.size() == 1);
//        Thread.sleep(10);

        // Then
        verify(bluezAdapter).setPowered(true);
    }

    @Test
    void Given_a_central_and_adapter_on_when_adapterOff_is_called_then_setPowered_false_is_called() throws InterruptedException {
        // Given
        when(bluezAdapter.isPowered()).thenReturn(true);

        // When
        BluetoothCentral central = new BluetoothCentral(callback, Collections.emptySet(), bluezAdapter);
        central.adapterOff();
        Thread.sleep(100);

        // Then
        verify(bluezAdapter).setPowered(false);
    }
    @Test
    void When_scanForPeripherals_is_called_then_an_unfiltered_scan_is_started() throws InterruptedException, BluezFailedException, BluezNotReadyException, BluezNotSupportedException, BluezInvalidArgumentsException {
        // Given
        when(bluezAdapter.isDiscovering()).thenReturn(false);
        when(bluezAdapter.isPowered()).thenReturn(true);
        BluetoothCentral central = new BluetoothCentral(callback, Collections.emptySet(), bluezAdapter);

        // When
        central.scanForPeripherals();

        // scanForPeripherals is async so wait a little bit
        Thread.sleep(100);

        // Then : Verify scan filters
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(bluezAdapter).setDiscoveryFilter(captor.capture());
        Map<String, Variant<?>> filterMap = captor.getValue();
        checkFilters(filterMap);

        // Then : Verify that no name, mac addresses or service UUID filters are set
        assertEquals(0, central.scanServiceUUIDs.length);
        assertEquals(0, central.scanPeripheralAddresses.length);
        assertEquals(0, central.scanPeripheralNames.length);

        // Then : Verify that scan is really started
        verify(bluezAdapter).startDiscovery();
    }

    private void checkFilters(Map<String, Variant<?>> filterMap) {
        assertEquals(3, filterMap.size());

        String transport = (String) filterMap.get("Transport").getValue();
        assertEquals("le", transport);

        Boolean duplicateData = (Boolean) filterMap.get("DuplicateData").getValue();
        assertTrue(duplicateData);

        Short rssi = (Short) filterMap.get("RSSI").getValue();
        assertEquals(DISCOVERY_RSSI_THRESHOLD, (int) rssi);
    }

    @Test
    void Given_a_scan_is_active_when_stopScan_is_called_then_the_scan_is_stopped() throws DBusException, InterruptedException {
        // Given
        BluetoothCentral central = startUnfilteredScan();
        Thread.sleep(100);
        when(bluezAdapter.isDiscovering()).thenReturn(true);

        // When
        central.stopScan();
        Thread.sleep(100);

        // Then
        verify(bluezAdapter).stopDiscovery();
    }

    @Test
    void When_scanning_unfiltered_and_an_InterfaceAdded_signal_comes_in_then_onDiscoveredPeripheral_is_called() throws InterruptedException, DBusException {
        // Given
        when(bluezAdapter.getPath(DUMMY_MAC_ADDRESS_BLP)).thenReturn(DUMMY_MAC_ADDRESS_PATH_BLP);
        when(bluezAdapter.getBluezDeviceByPath(DUMMY_MAC_ADDRESS_PATH_BLP)).thenReturn(bluezDevice);
        BluetoothCentral central = startUnfilteredScan();

        // When
        ObjectManager.InterfacesAdded interfacesAdded = getInterfacesAddedNewBlpDevice();
        central.handleInterfaceAddedForDevice(interfacesAdded.getPath(), interfacesAdded.getInterfaces().get(BLUEZ_DEVICE_INTERFACE));

        // Then
        ArgumentCaptor<BluetoothPeripheral> peripheralCaptor = ArgumentCaptor.forClass(BluetoothPeripheral.class);
        ArgumentCaptor<ScanResult> scanResultCaptor = ArgumentCaptor.forClass(ScanResult.class);
        verify(callback, timeout(500)).onDiscoveredPeripheral(peripheralCaptor.capture(), scanResultCaptor.capture());

        // Then : check if the peripheral and scanResult have the right values
        BluetoothPeripheral peripheral = peripheralCaptor.getValue();
        ScanResult scanResult = scanResultCaptor.getValue();
        assertEquals(DUMMY_MAC_ADDRESS_BLP, peripheral.getAddress());
        assertEquals(DUMMY_MAC_ADDRESS_BLP, scanResult.getAddress());
        assertEquals(DUMMY_PERIPHERAL_NAME_BLP, scanResult.getName());
        assertEquals(BLP_SERVICE_UUID, scanResult.getUuids().get(0));
        assertEquals(1, scanResult.getManufacturerData().size());
        assertEquals(1, scanResult.getServiceData().size());
    }

    @Test
    void When_scanning_unfiltered_and_a_PropertiesChanged_signal_comes_in_then_onDiscoveredPeripheral_is_called() throws InterruptedException, DBusException {
        // Given
        when(bluezAdapter.getPath(DUMMY_MAC_ADDRESS_BLP)).thenReturn(DUMMY_MAC_ADDRESS_PATH_BLP);
        when(bluezAdapter.getBluezDeviceByPath(DUMMY_MAC_ADDRESS_PATH_BLP)).thenReturn(bluezDevice);
        when(bluezDevice.getAddress()).thenReturn(DUMMY_MAC_ADDRESS_BLP);
        when(bluezDevice.getName()).thenReturn(DUMMY_PERIPHERAL_NAME_BLP);
        when(bluezDevice.getUuids()).thenReturn(Collections.singletonList(BLP_SERVICE_UUID));
        BluetoothCentral central = startUnfilteredScan();

        // When
        Properties.PropertiesChanged propertiesChanged = getPropertiesChangedSignalWhileScanning();
        central.handleSignal(propertiesChanged);

        // Then
        ArgumentCaptor<BluetoothPeripheral> peripheralCaptor = ArgumentCaptor.forClass(BluetoothPeripheral.class);
        ArgumentCaptor<ScanResult> scanResultCaptor = ArgumentCaptor.forClass(ScanResult.class);
        verify(callback, timeout(200)).onDiscoveredPeripheral(peripheralCaptor.capture(), scanResultCaptor.capture());

        // Then : check if the peripheral and scanResult have the right values
        BluetoothPeripheral peripheral = peripheralCaptor.getValue();
        ScanResult scanResult = scanResultCaptor.getValue();
        assertEquals(DUMMY_MAC_ADDRESS_BLP, peripheral.getAddress());
        assertEquals(DUMMY_MAC_ADDRESS_BLP, scanResult.getAddress());
        assertEquals(-32, scanResult.getRssi());
    }

    @Test
    void When_scanForPeripheralsWithServices_is_called_then_a_filtered_scan_is_started() throws InterruptedException, DBusException {
        // Given
        when(bluezAdapter.isDiscovering()).thenReturn(false);
        when(bluezAdapter.isPowered()).thenReturn(true);
        BluetoothCentral central = new BluetoothCentral(callback, Collections.emptySet(), bluezAdapter);

        // When
        central.scanForPeripheralsWithServices(new UUID[]{BLP_SERVICE_UUID});

        // scanForPeripheralsWithServices is async so wait a little bit
        Thread.sleep(100);

        // Then : Verify scan filters
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(bluezAdapter).setDiscoveryFilter(captor.capture());

        Map<String, Variant<?>> filterMap = captor.getValue();
        checkFilters(filterMap);

        // Then : Verify that no name, mac addresses or service UUID filters are set
        assertEquals(1, central.scanServiceUUIDs.length);
        assertEquals(BLP_SERVICE_UUID, central.scanServiceUUIDs[0]);
        assertEquals(0, central.scanPeripheralAddresses.length);
        assertEquals(0, central.scanPeripheralNames.length);

        // Then : Verify that startDiscovery is really called
        verify(bluezAdapter).startDiscovery();

        // Wait for properties changed to get confirmation the scan is started
        Properties.PropertiesChanged propertiesChangedSignal = getPropertiesChangeSignalDiscoveryStarted();
        central.handleSignal(propertiesChangedSignal);
        Thread.sleep(100);
        assertTrue(central.isScanning);
    }

    @Test
    void When_scanForPeripheralsWithServices_is_called_with_bad_arguments_then_exceptions_are_thrown() {
        // Given
        BluetoothCentral central = new BluetoothCentral(callback, Collections.emptySet(), bluezAdapter);

        assertThrows(NullPointerException.class, ()-> {
            central.scanForPeripheralsWithServices(null);
        });
        assertThrows(IllegalArgumentException.class, ()-> {
            central.scanForPeripheralsWithServices(new UUID[0]);
        });
    }

    @Test
    void When_scanning_for_service_and_a_matching_InterfaceAdded_signal_comes_in_then_onDiscoveredPeripheral_is_called() throws InterruptedException, DBusException {
        // Given
        when(bluezAdapter.getPath(DUMMY_MAC_ADDRESS_BLP)).thenReturn(DUMMY_MAC_ADDRESS_PATH_BLP);
        when(bluezAdapter.getBluezDeviceByPath(DUMMY_MAC_ADDRESS_PATH_BLP)).thenReturn(bluezDevice);
        BluetoothCentral central = startScanWithServices(BLP_SERVICE_UUID);

        // When
        ObjectManager.InterfacesAdded interfacesAdded = getInterfacesAddedNewBlpDevice();
        central.handleInterfaceAddedForDevice(interfacesAdded.getPath(), interfacesAdded.getInterfaces().get(BLUEZ_DEVICE_INTERFACE));
        Thread.sleep(100);

        // Then
        ArgumentCaptor<BluetoothPeripheral> peripheralCaptor = ArgumentCaptor.forClass(BluetoothPeripheral.class);
        ArgumentCaptor<ScanResult> scanResultCaptor = ArgumentCaptor.forClass(ScanResult.class);
        verify(callback).onDiscoveredPeripheral(peripheralCaptor.capture(), scanResultCaptor.capture());

        // Then : check if the peripheral and scanResult have the right values
        BluetoothPeripheral peripheral = peripheralCaptor.getValue();
        ScanResult scanResult = scanResultCaptor.getValue();
        assertEquals(DUMMY_MAC_ADDRESS_BLP, peripheral.getAddress());
        assertEquals(DUMMY_MAC_ADDRESS_BLP, scanResult.getAddress());
        assertEquals(DUMMY_PERIPHERAL_NAME_BLP, scanResult.getName());
        assertEquals(BLP_SERVICE_UUID, scanResult.getUuids().get(0));
        assertEquals(1, scanResult.getManufacturerData().size());
        assertEquals(1, scanResult.getServiceData().size());
    }

    @Test
    void When_scanning_for_service_and_a_matching_PropertiesChanged_signal_comes_in_then_onDiscoveredPeripheral_is_called() throws InterruptedException, DBusException {
        // Given
        when(bluezAdapter.getPath(DUMMY_MAC_ADDRESS_BLP)).thenReturn(DUMMY_MAC_ADDRESS_PATH_BLP);
        when(bluezAdapter.getBluezDeviceByPath(DUMMY_MAC_ADDRESS_PATH_BLP)).thenReturn(bluezDevice);
        when(bluezDevice.getAddress()).thenReturn(DUMMY_MAC_ADDRESS_BLP);
        when(bluezDevice.getName()).thenReturn(DUMMY_PERIPHERAL_NAME_BLP);
        when(bluezDevice.getUuids()).thenReturn(Collections.singletonList(BLP_SERVICE_UUID));
        BluetoothCentral central = startScanWithServices(BLP_SERVICE_UUID);

        // When
        Properties.PropertiesChanged propertiesChanged = getPropertiesChangedSignalWhileScanning();
        central.handleSignal(propertiesChanged);
        Thread.sleep(100);

        // Then
        ArgumentCaptor<BluetoothPeripheral> peripheralCaptor = ArgumentCaptor.forClass(BluetoothPeripheral.class);
        ArgumentCaptor<ScanResult> scanResultCaptor = ArgumentCaptor.forClass(ScanResult.class);
        verify(callback).onDiscoveredPeripheral(peripheralCaptor.capture(), scanResultCaptor.capture());

        // Then : check if the peripheral and scanResult have the right values
        BluetoothPeripheral peripheral = peripheralCaptor.getValue();
        ScanResult scanResult = scanResultCaptor.getValue();
        assertEquals(DUMMY_MAC_ADDRESS_BLP, peripheral.getAddress());
        assertEquals(DUMMY_MAC_ADDRESS_BLP, scanResult.getAddress());
        assertEquals(-32, scanResult.getRssi());
    }

    @Test
    void When_scanning_for_service_and_a_non_matching_InterFaceAdded_signal_comes_in_then_onDiscoveredPeripheral_is_not_called() throws InterruptedException, DBusException {
        // Given
        when(bluezAdapter.getPath(DUMMY_MAC_ADDRESS_HTS)).thenReturn(DUMMY_MAC_ADDRESS_PATH_HTS);
        when(bluezAdapter.getBluezDeviceByPath(DUMMY_MAC_ADDRESS_PATH_HTS)).thenReturn(bluezDeviceHts);
        BluetoothCentral central = startScanWithServices(BLP_SERVICE_UUID);

        // When
        ObjectManager.InterfacesAdded interfacesAdded = getInterfacesAddedNewHtsDevice();
        central.handleInterfaceAddedForDevice(interfacesAdded.getPath(), interfacesAdded.getInterfaces().get(BLUEZ_DEVICE_INTERFACE));
        Thread.sleep(100);

        // Then
        verify(callback, never()).onDiscoveredPeripheral(any(), any());
    }

    @Test
    void When_scanning_for_service_and_a_non_matching_PropertiesChanged_signal_comes_in_then_onDiscoveredPeripheral_is_not_called() throws InterruptedException, DBusException {
        // Given
        when(bluezAdapter.getPath(DUMMY_MAC_ADDRESS_BLP)).thenReturn(DUMMY_MAC_ADDRESS_PATH_BLP);
        when(bluezAdapter.getBluezDeviceByPath(DUMMY_MAC_ADDRESS_PATH_BLP)).thenReturn(bluezDevice);
        when(bluezDevice.getAddress()).thenReturn(DUMMY_MAC_ADDRESS_BLP);
        when(bluezDevice.getName()).thenReturn(DUMMY_PERIPHERAL_NAME_BLP);
        when(bluezDevice.getUuids()).thenReturn(Collections.singletonList(BLP_SERVICE_UUID));
        BluetoothCentral central = startScanWithServices(HTS_SERVICE_UUID);

        // When
        Properties.PropertiesChanged propertiesChanged = getPropertiesChangedSignalWhileScanning();
        central.handleSignal(propertiesChanged);
        Thread.sleep(100);

        // Then
        verify(callback, never()).onDiscoveredPeripheral(any(), any());
    }

    @Test
    void When_scanForPeripheralsWithAddresses_is_called_then_a_filtered_scan_is_started() throws InterruptedException, BluezFailedException, BluezNotReadyException, BluezNotSupportedException, BluezInvalidArgumentsException {
        // Given
        when(bluezAdapter.isDiscovering()).thenReturn(false);
        when(bluezAdapter.isPowered()).thenReturn(true);
        BluetoothCentral central = new BluetoothCentral(callback, Collections.emptySet(), bluezAdapter);

        // When
        central.scanForPeripheralsWithAddresses(new String[]{DUMMY_MAC_ADDRESS_BLP});

        // scanForPeripheralsWithServices is async so wait a little bit
        Thread.sleep(100);

        // Then : Verify scan filters
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(bluezAdapter).setDiscoveryFilter(captor.capture());

        Map<String, Variant<?>> filterMap = captor.getValue();
        checkFilters(filterMap);

        // Then : Verify that no name, mac addresses or service UUID filters are set
        assertEquals(0, central.scanServiceUUIDs.length);
        assertEquals(1, central.scanPeripheralAddresses.length);
        assertEquals(DUMMY_MAC_ADDRESS_BLP, central.scanPeripheralAddresses[0]);
        assertEquals(0, central.scanPeripheralNames.length);

        // Then : Verify that scan is really started
        verify(bluezAdapter).startDiscovery();
    }

    @Test
    void When_scanForPeripheralsWithAddresses_is_called_with_bad_arguments_then_exceptions_are_thrown() {
        // Given
        BluetoothCentral central = new BluetoothCentral(callback, Collections.emptySet(), bluezAdapter);

        assertThrows(NullPointerException.class, ()-> {
            central.scanForPeripheralsWithAddresses(null);
        });
        assertThrows(IllegalArgumentException.class, ()-> {
            central.scanForPeripheralsWithAddresses(new String[0]);
        });
    }

    @Test
    void When_scanning_for_address_and_a_matching_InterfaceAdded_signal_comes_in_then_onDiscoveredPeripheral_is_called() throws InterruptedException, DBusException {
        // Given
        when(bluezAdapter.getPath(DUMMY_MAC_ADDRESS_BLP)).thenReturn(DUMMY_MAC_ADDRESS_PATH_BLP);
        when(bluezAdapter.getBluezDeviceByPath(DUMMY_MAC_ADDRESS_PATH_BLP)).thenReturn(bluezDevice);
        BluetoothCentral central = startScanWithAddress(DUMMY_MAC_ADDRESS_BLP);

        // When
        ObjectManager.InterfacesAdded interfacesAdded = getInterfacesAddedNewBlpDevice();
        central.handleInterfaceAddedForDevice(interfacesAdded.getPath(), interfacesAdded.getInterfaces().get(BLUEZ_DEVICE_INTERFACE));
        Thread.sleep(100);

        // Then
        ArgumentCaptor<BluetoothPeripheral> peripheralCaptor = ArgumentCaptor.forClass(BluetoothPeripheral.class);
        ArgumentCaptor<ScanResult> scanResultCaptor = ArgumentCaptor.forClass(ScanResult.class);
        verify(callback).onDiscoveredPeripheral(peripheralCaptor.capture(), scanResultCaptor.capture());

        // Then : check if the peripheral and scanResult have the right values
        BluetoothPeripheral peripheral = peripheralCaptor.getValue();
        ScanResult scanResult = scanResultCaptor.getValue();
        assertEquals(DUMMY_MAC_ADDRESS_BLP, peripheral.getAddress());
        assertEquals(DUMMY_MAC_ADDRESS_BLP, scanResult.getAddress());
    }

    @Test
    void When_scanning_for_address_and_a_matching_PropertiesChanged_signal_comes_in_then_onDiscoveredPeripheral_is_called() throws InterruptedException, DBusException {
        // Given
        when(bluezAdapter.getPath(DUMMY_MAC_ADDRESS_BLP)).thenReturn(DUMMY_MAC_ADDRESS_PATH_BLP);
        when(bluezAdapter.getBluezDeviceByPath(DUMMY_MAC_ADDRESS_PATH_BLP)).thenReturn(bluezDevice);
        when(bluezDevice.getAddress()).thenReturn(DUMMY_MAC_ADDRESS_BLP);
        when(bluezDevice.getName()).thenReturn(DUMMY_PERIPHERAL_NAME_BLP);
        when(bluezDevice.getUuids()).thenReturn(Collections.singletonList(BLP_SERVICE_UUID));
        BluetoothCentral central = startScanWithAddress(DUMMY_MAC_ADDRESS_BLP);

        // When
        Properties.PropertiesChanged propertiesChanged = getPropertiesChangedSignalWhileScanning();
        central.handleSignal(propertiesChanged);
        Thread.sleep(100);

        // Then
        ArgumentCaptor<BluetoothPeripheral> peripheralCaptor = ArgumentCaptor.forClass(BluetoothPeripheral.class);
        ArgumentCaptor<ScanResult> scanResultCaptor = ArgumentCaptor.forClass(ScanResult.class);
        verify(callback).onDiscoveredPeripheral(peripheralCaptor.capture(), scanResultCaptor.capture());

        // Then : check if the peripheral and scanResult have the right values
        BluetoothPeripheral peripheral = peripheralCaptor.getValue();
        ScanResult scanResult = scanResultCaptor.getValue();
        assertEquals(DUMMY_MAC_ADDRESS_BLP, peripheral.getAddress());
        assertEquals(DUMMY_MAC_ADDRESS_BLP, scanResult.getAddress());
        assertEquals(-32, scanResult.getRssi());
    }

    @Test
    void When_scanning_for_address_and_a_non_matching_InterfaceAdded_signal_comes_in_then_onDiscoveredPeripheral_is_not_called() throws InterruptedException, DBusException {
        // Given
        when(bluezAdapter.getPath(DUMMY_MAC_ADDRESS_HTS)).thenReturn(DUMMY_MAC_ADDRESS_PATH_HTS);
        when(bluezAdapter.getBluezDeviceByPath(DUMMY_MAC_ADDRESS_PATH_HTS)).thenReturn(bluezDeviceHts);
        BluetoothCentral central = startScanWithAddress(DUMMY_MAC_ADDRESS_BLP);

        // When
        ObjectManager.InterfacesAdded interfacesAdded = getInterfacesAddedNewHtsDevice();
        central.handleInterfaceAddedForDevice(interfacesAdded.getPath(), interfacesAdded.getInterfaces().get(BLUEZ_DEVICE_INTERFACE));
        Thread.sleep(100);

        // Then
        verify(callback, never()).onDiscoveredPeripheral(any(), any());
    }

    @Test
    void When_scanning_for_address_and_a_non_matching_PropertiesChanged_signal_comes_in_then_onDiscoveredPeripheral_is_not_called() throws InterruptedException, DBusException {
        // Given
        when(bluezAdapter.getPath(DUMMY_MAC_ADDRESS_BLP)).thenReturn(DUMMY_MAC_ADDRESS_PATH_BLP);
        when(bluezAdapter.getBluezDeviceByPath(DUMMY_MAC_ADDRESS_PATH_BLP)).thenReturn(bluezDevice);
        when(bluezDevice.getAddress()).thenReturn(DUMMY_MAC_ADDRESS_BLP);
        when(bluezDevice.getName()).thenReturn(DUMMY_PERIPHERAL_NAME_BLP);
        when(bluezDevice.getUuids()).thenReturn(Collections.singletonList(BLP_SERVICE_UUID));
        BluetoothCentral central = startScanWithAddress(DUMMY_MAC_ADDRESS_HTS);

        // When
        Properties.PropertiesChanged propertiesChanged = getPropertiesChangedSignalWhileScanning();
        central.handleSignal(propertiesChanged);
        Thread.sleep(100);

        // Then
        verify(callback, never()).onDiscoveredPeripheral(any(), any());
    }

    @Test
    void When_scanForPeripheralsWithNames_is_called_then_a_filtered_scan_is_started() throws InterruptedException, BluezFailedException, BluezNotReadyException, BluezNotSupportedException, BluezInvalidArgumentsException {
        // Given
        when(bluezAdapter.isDiscovering()).thenReturn(false);
        when(bluezAdapter.isPowered()).thenReturn(true);
        BluetoothCentral central = new BluetoothCentral(callback, Collections.emptySet(), bluezAdapter);

        // When
        central.scanForPeripheralsWithNames(new String[]{DUMMY_PERIPHERAL_NAME_BLP});

        // scanForPeripheralsWithServices is async so wait a little bit
        Thread.sleep(100);

        // Then : Verify scan filters
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(bluezAdapter).setDiscoveryFilter(captor.capture());
        Map<String, Variant<?>> filterMap = captor.getValue();
        checkFilters(filterMap);

        // Then : Verify that no name, mac addresses or service UUID filters are set
        assertEquals(0, central.scanServiceUUIDs.length);
        assertEquals(0, central.scanPeripheralAddresses.length);
        assertEquals(1, central.scanPeripheralNames.length);
        assertEquals(DUMMY_PERIPHERAL_NAME_BLP, central.scanPeripheralNames[0]);

        // Then : Verify that scan is really started
        verify(bluezAdapter).startDiscovery();
    }

    @Test
    void When_scanForPeripheralsWithNames_is_called_with_bad_arguments_then_exceptions_are_thrown() {
        // Given
        BluetoothCentral central = new BluetoothCentral(callback, Collections.emptySet(), bluezAdapter);

        assertThrows(NullPointerException.class, ()-> {
            central.scanForPeripheralsWithNames(null);
        });
        assertThrows(IllegalArgumentException.class, ()-> {
            central.scanForPeripheralsWithNames(new String[0]);
        });
    }

    @Test
    void When_scanning_for_names_and_matching_InterfaceAdded_signal_comes_in_then_onDiscoveredPeripheral_is_called() throws InterruptedException, DBusException {
        // Given
        when(bluezAdapter.getPath(DUMMY_MAC_ADDRESS_BLP)).thenReturn(DUMMY_MAC_ADDRESS_PATH_BLP);
        when(bluezAdapter.getBluezDeviceByPath(DUMMY_MAC_ADDRESS_PATH_BLP)).thenReturn(bluezDevice);
        BluetoothCentral central = startScanWithNames(DUMMY_PERIPHERAL_NAME_BLP);

        // When
        ObjectManager.InterfacesAdded interfacesAdded = getInterfacesAddedNewBlpDevice();
        central.handleInterfaceAddedForDevice(interfacesAdded.getPath(), interfacesAdded.getInterfaces().get(BLUEZ_DEVICE_INTERFACE));
        Thread.sleep(100);

        // Then
        ArgumentCaptor<BluetoothPeripheral> peripheralCaptor = ArgumentCaptor.forClass(BluetoothPeripheral.class);
        ArgumentCaptor<ScanResult> scanResultCaptor = ArgumentCaptor.forClass(ScanResult.class);
        verify(callback).onDiscoveredPeripheral(peripheralCaptor.capture(), scanResultCaptor.capture());

        // Then : check if the peripheral and scanResult have the right values
        BluetoothPeripheral peripheral = peripheralCaptor.getValue();
        ScanResult scanResult = scanResultCaptor.getValue();
        assertEquals(DUMMY_MAC_ADDRESS_BLP, peripheral.getAddress());
        assertEquals(DUMMY_MAC_ADDRESS_BLP, scanResult.getAddress());
        assertEquals(DUMMY_PERIPHERAL_NAME_BLP, scanResult.getName());
    }

    @Test
    void When_scanning_for_names_and_a_matching_PropertiesChanged_signal_comes_in_then_onDiscoveredPeripheral_is_called() throws InterruptedException, DBusException {
        // Given
        when(bluezAdapter.getPath(DUMMY_MAC_ADDRESS_BLP)).thenReturn(DUMMY_MAC_ADDRESS_PATH_BLP);
        when(bluezAdapter.getBluezDeviceByPath(DUMMY_MAC_ADDRESS_PATH_BLP)).thenReturn(bluezDevice);
        when(bluezDevice.getAddress()).thenReturn(DUMMY_MAC_ADDRESS_BLP);
        when(bluezDevice.getName()).thenReturn(DUMMY_PERIPHERAL_NAME_BLP);
        when(bluezDevice.getUuids()).thenReturn(Collections.singletonList(BLP_SERVICE_UUID));
        BluetoothCentral central = startScanWithNames(DUMMY_PERIPHERAL_NAME_BLP);

        // When
        Properties.PropertiesChanged propertiesChanged = getPropertiesChangedSignalWhileScanning();
        central.handleSignal(propertiesChanged);
        Thread.sleep(100);

        // Then
        ArgumentCaptor<BluetoothPeripheral> peripheralCaptor = ArgumentCaptor.forClass(BluetoothPeripheral.class);
        ArgumentCaptor<ScanResult> scanResultCaptor = ArgumentCaptor.forClass(ScanResult.class);
        verify(callback).onDiscoveredPeripheral(peripheralCaptor.capture(), scanResultCaptor.capture());

        // Then : check if the peripheral and scanResult have the right values
        BluetoothPeripheral peripheral = peripheralCaptor.getValue();
        ScanResult scanResult = scanResultCaptor.getValue();
        assertEquals(DUMMY_MAC_ADDRESS_BLP, peripheral.getAddress());
        assertEquals(DUMMY_MAC_ADDRESS_BLP, scanResult.getAddress());
        assertEquals(-32, scanResult.getRssi());
    }

    @Test
    void When_scanning_for_names_and_non_matching_InterfaceAdded_signal_comes_in_then_onDiscoveredPeripheral_is_not_called() throws InterruptedException, DBusException {
        // Given
        when(bluezAdapter.getPath(DUMMY_MAC_ADDRESS_HTS)).thenReturn(DUMMY_MAC_ADDRESS_PATH_HTS);
        when(bluezAdapter.getBluezDeviceByPath(DUMMY_MAC_ADDRESS_PATH_HTS)).thenReturn(bluezDeviceHts);
        BluetoothCentral central = startScanWithNames(DUMMY_PERIPHERAL_NAME_BLP);

        // When
        ObjectManager.InterfacesAdded interfacesAdded = getInterfacesAddedNewHtsDevice();
        central.handleInterfaceAddedForDevice(interfacesAdded.getPath(), interfacesAdded.getInterfaces().get(BLUEZ_DEVICE_INTERFACE));
        Thread.sleep(100);

        // Then
        verify(callback, never()).onDiscoveredPeripheral(any(), any());
    }

    @Test
    void When_scanning_for_names_and_a_non_matching_PropertiesChanged_signal_comes_in_then_onDiscoveredPeripheral_is_not_called() throws InterruptedException, DBusException {
        // Given
        when(bluezAdapter.getPath(DUMMY_MAC_ADDRESS_BLP)).thenReturn(DUMMY_MAC_ADDRESS_PATH_BLP);
        when(bluezAdapter.getBluezDeviceByPath(DUMMY_MAC_ADDRESS_PATH_BLP)).thenReturn(bluezDevice);
        when(bluezDevice.getAddress()).thenReturn(DUMMY_MAC_ADDRESS_BLP);
        when(bluezDevice.getName()).thenReturn("Something else");
        when(bluezDevice.getUuids()).thenReturn(Collections.singletonList(BLP_SERVICE_UUID));
        BluetoothCentral central = startScanWithNames(DUMMY_PERIPHERAL_NAME_BLP);

        // When
        Properties.PropertiesChanged propertiesChanged = getPropertiesChangedSignalWhileScanning();
        central.handleSignal(propertiesChanged);
        Thread.sleep(100);

        // Then
        verify(callback, never()).onDiscoveredPeripheral(any(), any());
    }

    @Test
    void Given_a_scan_is_running_when_connectionPeripheral_is_called_then_the_scan_is_stopped() throws DBusException, InterruptedException {
        // Given
        when(bluezAdapter.getPath(DUMMY_MAC_ADDRESS_BLP)).thenReturn(DUMMY_MAC_ADDRESS_PATH_BLP);
        when(bluezAdapter.getBluezDeviceByPath(DUMMY_MAC_ADDRESS_PATH_BLP)).thenReturn(bluezDevice);
        BluetoothCentral central = startUnfilteredScan();
        Thread.sleep(100);
        when(bluezAdapter.isDiscovering()).thenReturn(true);

        // When
        BluetoothPeripheral peripheral = central.getPeripheral(DUMMY_MAC_ADDRESS_BLP);
        central.connectPeripheral(peripheral, peripheralCallback);

        Thread.sleep(100);

        // Then
        verify(bluezAdapter).stopDiscovery();
    }

    @Test
    void When_connectPeripheral_is_called_then_a_connection_attempt_is_done_after_a_delay() throws BluezFailedException, BluezAlreadyConnectedException, BluezNotReadyException, BluezInProgressException, InterruptedException {
        // Given
        when(bluezAdapter.getPath(DUMMY_MAC_ADDRESS_BLP)).thenReturn(DUMMY_MAC_ADDRESS_PATH_BLP);
        when(bluezAdapter.getBluezDeviceByPath(DUMMY_MAC_ADDRESS_PATH_BLP)).thenReturn(bluezDevice);
        when(bluezAdapter.isPowered()).thenReturn(true);
        BluezSignalHandler.createInstance(dBusConnection);
        BluetoothCentral central = new BluetoothCentral(callback, Collections.emptySet(), bluezAdapter);

        // When
        BluetoothPeripheral peripheral = central.getPeripheral(DUMMY_MAC_ADDRESS_BLP);
        central.connectPeripheral(peripheral, peripheralCallback);

        // Then
        Thread.sleep(CONNECT_DELAY-50);
        verify(bluezDevice, never()).connect();

        Thread.sleep(CONNECT_DELAY+50);
        verify(bluezDevice).connect();
    }

    @Test
    void Given_a_disconnected_peripheral_when_a_peripherals_connects_then_onConnected_is_called() throws DBusException, InterruptedException {
        // Given
        BluetoothCentral central = getCentral();
        when(bluezAdapter.getPath(DUMMY_MAC_ADDRESS_BLP)).thenReturn(DUMMY_MAC_ADDRESS_PATH_BLP);
        when(bluezAdapter.getBluezDeviceByPath(DUMMY_MAC_ADDRESS_PATH_BLP)).thenReturn(bluezDevice);
        BluetoothPeripheral peripheral = central.getPeripheral(DUMMY_MAC_ADDRESS_BLP);

        // When
        connectPeripheral(central, peripheral);

        // Then
        verify(callback).onConnectedPeripheral(peripheral);
    }

    @Test
    void Given_a_connected_peripheral_and_scanning_when_cancelPeripheralConnection_is_called_then_the_scan_is_stopped() throws DBusException, InterruptedException {
        // Given
        BluetoothCentral central = getCentral();

        when(bluezAdapter.getPath(DUMMY_MAC_ADDRESS_BLP)).thenReturn(DUMMY_MAC_ADDRESS_PATH_BLP);
        when(bluezAdapter.getBluezDeviceByPath(DUMMY_MAC_ADDRESS_PATH_BLP)).thenReturn(bluezDevice);
        BluetoothPeripheral peripheral = central.getPeripheral(DUMMY_MAC_ADDRESS_BLP);
        connectPeripheral(central, peripheral);
        startScan(central);

        // When
        central.cancelConnection(peripheral);
        Thread.sleep(100);

        // Then
        verify(bluezAdapter).stopDiscovery();
    }

    @Test
    void Given_a_connected_peripheral_when_cancelPeripheralConnection_is_called_then_the_peripheral_is_disconnected() throws DBusException, InterruptedException {
        // Given
        BluetoothCentral central = getCentral();
        when(bluezAdapter.getPath(DUMMY_MAC_ADDRESS_BLP)).thenReturn(DUMMY_MAC_ADDRESS_PATH_BLP);
        when(bluezAdapter.getBluezDeviceByPath(DUMMY_MAC_ADDRESS_PATH_BLP)).thenReturn(bluezDevice);
        BluetoothPeripheral peripheral = central.getPeripheral(DUMMY_MAC_ADDRESS_BLP);
        connectPeripheral(central, peripheral);

        // When
        central.cancelConnection(peripheral);
        Thread.sleep(100);

        // Then
        verify(bluezDevice).disconnect();
    }

    @Test
    void Given_a_connected_peripheral_when_it_disconnects_then_onDisconnect_is_called() throws DBusException, InterruptedException {
        // Given
        BluetoothCentral central = getCentral();
        when(bluezAdapter.getPath(DUMMY_MAC_ADDRESS_BLP)).thenReturn(DUMMY_MAC_ADDRESS_PATH_BLP);
        when(bluezAdapter.getBluezDeviceByPath(DUMMY_MAC_ADDRESS_PATH_BLP)).thenReturn(bluezDevice);
        BluetoothPeripheral peripheral = central.getPeripheral(DUMMY_MAC_ADDRESS_BLP);
        connectPeripheral(central, peripheral);

        // When
        central.cancelConnection(peripheral);
        Thread.sleep(100);
        Properties.PropertiesChanged disconnectedSignal = getPropertiesChangedSignalDisconnected();
        peripheral.handleSignal(disconnectedSignal);
        Thread.sleep(100);

        // Then
        verify(callback).onDisconnectedPeripheral(peripheral, GATT_SUCCESS);
    }

    @Test
    void Given_a_disconnected_peripheral_when_autoConnect_is_called_it_is_added_to_reconnection_list() throws DBusException, InterruptedException {
        // Given
        BluetoothCentral central = getCentral();
        when(bluezAdapter.getPath(DUMMY_MAC_ADDRESS_BLP)).thenReturn(DUMMY_MAC_ADDRESS_PATH_BLP);
        when(bluezAdapter.getBluezDeviceByPath(DUMMY_MAC_ADDRESS_PATH_BLP)).thenReturn(bluezDevice);
        BluetoothPeripheral peripheral = central.getPeripheral(DUMMY_MAC_ADDRESS_BLP);

        // When
        central.autoConnectPeripheral(peripheral, peripheralCallback);

        // Then
        assertTrue(central.reconnectPeripheralAddresses.contains(peripheral.getAddress()));
        assertSame(central.reconnectCallbacks.get(peripheral.getAddress()), peripheralCallback);
    }

    @Test
    void Given_a_disconnected_peripheral_and_not_scanning_when_autoConnect_is_called_a_scan_is_started() throws DBusException, InterruptedException {
        // Given
        BluetoothCentral central = getCentral();
        when(bluezAdapter.getPath(DUMMY_MAC_ADDRESS_BLP)).thenReturn(DUMMY_MAC_ADDRESS_PATH_BLP);
        when(bluezAdapter.getBluezDeviceByPath(DUMMY_MAC_ADDRESS_PATH_BLP)).thenReturn(bluezDevice);
        BluetoothPeripheral peripheral = central.getPeripheral(DUMMY_MAC_ADDRESS_BLP);

        // When
        central.autoConnectPeripheral(peripheral, peripheralCallback);
        Thread.sleep(100);

        // Then
        verify(bluezAdapter).startDiscovery();
    }

    @Test
    void Given_an_autoConnect_is_issued_when_the_peripheral_is_seen_then_a_connect_is_attempted_and_it_is_removed_from_list() throws InterruptedException, DBusException {
        // Given
        BluetoothCentral central = getCentral();
        when(bluezAdapter.getPath(DUMMY_MAC_ADDRESS_BLP)).thenReturn(DUMMY_MAC_ADDRESS_PATH_BLP);
        when(bluezAdapter.getBluezDeviceByPath(DUMMY_MAC_ADDRESS_PATH_BLP)).thenReturn(bluezDevice);
        when(bluezDevice.getAddress()).thenReturn(DUMMY_MAC_ADDRESS_BLP);
        when(bluezDevice.getName()).thenReturn("Something else");
        when(bluezDevice.getUuids()).thenReturn(Collections.singletonList(BLP_SERVICE_UUID));
        BluetoothPeripheral peripheral = central.getPeripheral(DUMMY_MAC_ADDRESS_BLP);
        central.autoConnectPeripheral(peripheral, peripheralCallback);
        Thread.sleep(100);
        central.handleSignal(getPropertiesChangeSignalDiscoveryStarted());
        Thread.sleep(100);
        when(bluezAdapter.isDiscovering()).thenReturn(true);

        // When
        central.handleSignal(getPropertiesChangedSignalWhileScanning());
        Thread.sleep(100);

        // Then
        verify(bluezAdapter).stopDiscovery();

        central.handleSignal(getPropertiesChangeSignalDiscoveryStopped());
        when(bluezAdapter.isDiscovering()).thenReturn(false);
        Thread.sleep(500);

        verify(bluezDevice).connect();

        assertFalse(central.reconnectPeripheralAddresses.contains(peripheral.getAddress()));
        assertNull(central.reconnectCallbacks.get(peripheral.getAddress()));
    }

    @Test
    void Given_two_disconnected_devices_when_autoConnectBatch_is_called_both_are_added_to_list_and_scan_is_started() throws BluezFailedException, BluezNotReadyException, InterruptedException {
        // Given
        BluetoothCentral central = getCentral();
        when(bluezAdapter.getPath(DUMMY_MAC_ADDRESS_BLP)).thenReturn(DUMMY_MAC_ADDRESS_PATH_BLP);
        when(bluezAdapter.getBluezDeviceByPath(DUMMY_MAC_ADDRESS_PATH_BLP)).thenReturn(bluezDevice);
        when(bluezAdapter.getPath(DUMMY_MAC_ADDRESS_HTS)).thenReturn(DUMMY_MAC_ADDRESS_PATH_HTS);
        when(bluezAdapter.getBluezDeviceByPath(DUMMY_MAC_ADDRESS_PATH_HTS)).thenReturn(bluezDeviceHts);
        BluetoothPeripheral peripheral1 = central.getPeripheral(DUMMY_MAC_ADDRESS_BLP);
        BluetoothPeripheral peripheral2 = central.getPeripheral(DUMMY_MAC_ADDRESS_HTS);

        // When
        Map<BluetoothPeripheral, BluetoothPeripheralCallback> map = new HashMap<>();
        map.put(peripheral1, peripheralCallback);
        map.put(peripheral2, peripheralCallback);
        central.autoConnectPeripheralsBatch(map);

        // Then
        assertTrue(central.reconnectPeripheralAddresses.contains(peripheral1.getAddress()));
        assertSame(central.reconnectCallbacks.get(peripheral1.getAddress()), peripheralCallback);
        assertTrue(central.reconnectPeripheralAddresses.contains(peripheral2.getAddress()));
        assertSame(central.reconnectCallbacks.get(peripheral2.getAddress()), peripheralCallback);

        Thread.sleep(100);

        // Then
        verify(bluezAdapter).startDiscovery();
    }

    @Test
    void When_setPinCode_is_called_with_valid_inputs_it_is_stored_by_central() {
        // Given
        BluetoothCentral central = getCentral();

        // When
        central.setPinCodeForPeripheral(DUMMY_MAC_ADDRESS_BLP, "123456");

        // Then
        assertTrue(central.pinCodes.containsKey(DUMMY_MAC_ADDRESS_BLP));
        assertEquals(central.pinCodes.get(DUMMY_MAC_ADDRESS_BLP), "123456");
    }

    @Test
    void When_setPinCode_is_called_with_invalid_macaddress_it_is_not_stored_by_central() {
        // Given
        BluetoothCentral central = getCentral();

        // When
        central.setPinCodeForPeripheral(DUMMY_MAC_ADDRESS_BLP+"A", "123456");

        // Then
        assertFalse(central.pinCodes.containsKey(DUMMY_MAC_ADDRESS_BLP));
    }

    @Test
    void When_setPinCode_is_called_with_invalid_pincode_it_is_not_stored_by_central() {
        // Given
        BluetoothCentral central = getCentral();

        // When
        central.setPinCodeForPeripheral(DUMMY_MAC_ADDRESS_BLP, "12345");

        // Then
        assertFalse(central.pinCodes.containsKey(DUMMY_MAC_ADDRESS_BLP));
    }

    @NotNull
    private BluetoothCentral getCentral() {
        BluezSignalHandler.createInstance(dBusConnection);
        when(bluezAdapter.isPowered()).thenReturn(true);
        return new BluetoothCentral(callback, Collections.emptySet(), bluezAdapter);
    }

    private void startScan(BluetoothCentral central) throws InterruptedException, DBusException {
        central.scanForPeripherals();
        Thread.sleep(10);
        Properties.PropertiesChanged propertiesChangedSignal = getPropertiesChangeSignalDiscoveryStarted();
        central.handleSignal(propertiesChangedSignal);
        Thread.sleep(10);
        when(bluezAdapter.isDiscovering()).thenReturn(true);
    }

    private void connectPeripheral(BluetoothCentral central, BluetoothPeripheral peripheral) throws InterruptedException, DBusException {
        central.connectPeripheral(peripheral, peripheralCallback);
        Thread.sleep(500);
        peripheral.handleSignal(getPropertiesChangedSignalConnected());
        Thread.sleep(10);
        assertEquals(STATE_CONNECTED, peripheral.getState());
        peripheral.handleSignal(getPropertiesChangedSignalServicesResolved());
        Thread.sleep(10);
    }

    @NotNull
    private Properties.PropertiesChanged getPropertiesChangedSignalConnected() throws DBusException {
        Map<String, Variant<?>> propertiesChanged = new HashMap<>();
        propertiesChanged.put(PROPERTY_CONNECTED, new Variant<>(true));
        return new Properties.PropertiesChanged("/org/bluez/hci0", BLUEZ_DEVICE_INTERFACE, propertiesChanged,new ArrayList() );
    }

    @NotNull
    private Properties.PropertiesChanged getPropertiesChangedSignalDisconnected() throws DBusException {
        Map<String, Variant<?>> propertiesChanged = new HashMap<>();
        propertiesChanged.put(PROPERTY_CONNECTED, new Variant<>(false));
        return new Properties.PropertiesChanged("/org/bluez/hci0", BLUEZ_DEVICE_INTERFACE, propertiesChanged,new ArrayList() );
    }

    @NotNull
    private Properties.PropertiesChanged getPropertiesChangedSignalServicesResolved() throws DBusException {
        Map<String, Variant<?>> propertiesChanged = new HashMap<>();
        propertiesChanged.put(PROPERTY_SERVICES_RESOLVED, new Variant<>(true));
        return new Properties.PropertiesChanged("/org/bluez/hci0/dev_C0_26_DF_01_F2_72", BLUEZ_DEVICE_INTERFACE, propertiesChanged,new ArrayList() );
    }

    @NotNull
    private Properties.PropertiesChanged getPropertiesChangeSignalDiscoveryStarted() throws DBusException {
        Map<String, Variant<?>> propertiesChanged = new HashMap<>();
        propertiesChanged.put(PROPERTY_DISCOVERING, new Variant<>(true));
        return new Properties.PropertiesChanged("/org/bluez/hci0", BLUEZ_ADAPTER_INTERFACE, propertiesChanged,new ArrayList() );
    }

    @NotNull
    private Properties.PropertiesChanged getPropertiesChangeSignalDiscoveryStopped() throws DBusException {
        Map<String, Variant<?>> propertiesChanged = new HashMap<>();
        propertiesChanged.put(PROPERTY_DISCOVERING, new Variant<>(false));
        return new Properties.PropertiesChanged("/org/bluez/hci0", BLUEZ_ADAPTER_INTERFACE, propertiesChanged,new ArrayList() );
    }

    @NotNull
    private Properties.PropertiesChanged getPropertiesChangedSignalWhileScanning() throws DBusException {
        Map<String, Variant<?>> propertiesChanged = new HashMap<>();
        propertiesChanged.put(PROPERTY_ADDRESS, new Variant<>(DUMMY_MAC_ADDRESS_BLP));
        propertiesChanged.put(PROPERTY_RSSI, new Variant<>(new Short("-32")));
        Object[][] testObjects = new Object[1][2];
        testObjects[0][0] = new UInt16(100);
        testObjects[0][1] = new Variant<>(new byte[]{0x10,0x20}, "ay");
        DBusMap<UInt16, byte[]> manufacturerData = new DBusMap<>(testObjects);
        propertiesChanged.put(PROPERTY_MANUFACTURER_DATA, new Variant<>(manufacturerData, "a{qv}" ));
        Map<String, Variant<?>> serviceData = new HashMap<>();
        serviceData.put(BLP_SERVICE_UUID.toString(), new Variant<>(new byte[]{0x44, 0x55}));
        propertiesChanged.put(PROPERTY_SERVICE_DATA, new Variant<>(convertStringHashMapToDBusMap(serviceData), "a{sv}"));
        String dBusPath = DUMMY_MAC_ADDRESS_PATH_BLP;
        return new Properties.PropertiesChanged(dBusPath, BLUEZ_DEVICE_INTERFACE, propertiesChanged,new ArrayList() );
    }

    @NotNull
    private ObjectManager.InterfacesAdded getInterfacesAddedNewBlpDevice() throws DBusException {
        String objectPath = "/";
        DBusPath dBusPath = new DBusPath("/org/bluez/hci0/dev_00_11_22_33_44_55");
        HashMap<String, Map<String, Variant<?>>> interfaceAddedMap = new HashMap<>();
        Map<String, Variant<?>> interfaceMap = new HashMap<>();

        interfaceMap.put(PROPERTY_ADDRESS, new Variant<>(DUMMY_MAC_ADDRESS_BLP));
        interfaceMap.put(PROPERTY_NAME, new Variant<>(DUMMY_PERIPHERAL_NAME_BLP));
        interfaceMap.put(PROPERTY_CONNECTED, new Variant<>(false));
        interfaceMap.put(PROPERTY_SERVICES_RESOLVED, new Variant<>(false));
        interfaceMap.put(PROPERTY_PAIRED, new Variant<>(false));
        interfaceMap.put(PROPERTY_RSSI, new Variant<>(new Short("-50")));
        ArrayList<String> uuids = new ArrayList<>();
        uuids.add(BLP_SERVICE_UUID.toString());
        interfaceMap.put(PROPERTY_SERVICE_UUIDS, new Variant<>(uuids, "as"));

        // Build Manufacturer Data
        Object[][] testObjects = new Object[1][2];
        testObjects[0][0] = new UInt16(41);
        testObjects[0][1] = new Variant<>(new byte[]{0x10,0x20}, "ay");
        DBusMap<UInt16, byte[]> manufacturerData = new DBusMap<>(testObjects);
        interfaceMap.put(PROPERTY_MANUFACTURER_DATA, new Variant<>(manufacturerData, "a{qv}" ));

        // Build Service Data
        Map<String, Variant<?>> serviceData = new HashMap<>();
        serviceData.put(BLP_SERVICE_UUID.toString(), new Variant<>(new byte[]{0x54, 0x12}));
        interfaceMap.put(PROPERTY_SERVICE_DATA, new Variant<>(convertStringHashMapToDBusMap(serviceData), "a{sv}"));

        DBusMap<String, Variant<?>> finalInterfacesAddedMap = convertStringHashMapToDBusMap(interfaceMap);
        interfaceAddedMap.put(BLUEZ_DEVICE_INTERFACE, finalInterfacesAddedMap);
        return new ObjectManager.InterfacesAdded(objectPath,dBusPath, interfaceAddedMap);
    }

    @NotNull
    private ObjectManager.InterfacesAdded getInterfacesAddedNewHtsDevice() throws DBusException {
        String objectPath = "/";
        DBusPath dBusPath = new DBusPath("/org/bluez/hci0/dev_00_11_44_33_44_55");
        HashMap<String, Map<String, Variant<?>>> interfaceAddedMap = new HashMap<>();
        Map<String, Variant<?>> interfaceMap = new HashMap<>();

        interfaceMap.put(PROPERTY_ADDRESS, new Variant<>(DUMMY_MAC_ADDRESS_HTS));
        interfaceMap.put(PROPERTY_NAME, new Variant<>(DUMMY_PERIPHERAL_NAME_HTS));
        interfaceMap.put(PROPERTY_CONNECTED, new Variant<>(false));
        interfaceMap.put(PROPERTY_SERVICES_RESOLVED, new Variant<>(false));
        interfaceMap.put(PROPERTY_PAIRED, new Variant<>(false));
        interfaceMap.put(PROPERTY_RSSI, new Variant<>(new Short("-32")));
        ArrayList<String> uuids = new ArrayList<>();
        uuids.add(HTS_SERVICE_UUID.toString());
        interfaceMap.put(PROPERTY_SERVICE_UUIDS, new Variant<>(uuids, "as"));

        // Build Manufacturer Data
        Object[][] testObjects = new Object[1][2];
        testObjects[0][0] = new UInt16(41);
        testObjects[0][1] = new Variant<>(new byte[]{0x20,0x21}, "ay");
        DBusMap<UInt16, byte[]> manufacturerData = new DBusMap<>(testObjects);
        interfaceMap.put(PROPERTY_MANUFACTURER_DATA, new Variant<>(manufacturerData, "a{qv}" ));

        // Build Service Data
        Map<String, Variant<?>> serviceData = new HashMap<>();
        serviceData.put(BLP_SERVICE_UUID.toString(), new Variant<>(new byte[]{0x14, 0x13}));
        interfaceMap.put(PROPERTY_SERVICE_DATA, new Variant<>(convertStringHashMapToDBusMap(serviceData), "a{sv}"));

        DBusMap<String, Variant<?>> finalInterfacesAddedMap = convertStringHashMapToDBusMap(interfaceMap);
        interfaceAddedMap.put(BLUEZ_DEVICE_INTERFACE, finalInterfacesAddedMap);
        return new ObjectManager.InterfacesAdded(objectPath,dBusPath, interfaceAddedMap);
    }

    private DBusMap<String, Variant<?>> convertStringHashMapToDBusMap(Map<String, Variant<?>> source) {
        Objects.requireNonNull(source);
        Object[][] result = new Object[source.size()][2];
        int index = 0;
        for (Map.Entry<String, Variant<?>> entry : source.entrySet() ) {
            result[index][0]= entry.getKey();
            result[index][1] = entry.getValue();
            index++;
        }
        return new DBusMap<>(result);
    }

    private BluetoothCentral startUnfilteredScan() throws InterruptedException, DBusException {
        when(bluezAdapter.isDiscovering()).thenReturn(false);
        when(bluezAdapter.isPowered()).thenReturn(true);
        BluetoothCentral central = new BluetoothCentral(callback, Collections.emptySet(), bluezAdapter);
        central.scanForPeripherals();
        Thread.sleep(10);
        Properties.PropertiesChanged propertiesChangedSignal = getPropertiesChangeSignalDiscoveryStarted();
        central.handleSignal(propertiesChangedSignal);
        Thread.sleep(10);
        return central;
    }

    private BluetoothCentral startScanWithServices(UUID service) throws InterruptedException, DBusException {
        when(bluezAdapter.isDiscovering()).thenReturn(false);
        when(bluezAdapter.isPowered()).thenReturn(true);
        BluetoothCentral central = new BluetoothCentral(callback, Collections.emptySet(), bluezAdapter);
        central.scanForPeripheralsWithServices(new UUID[]{service});
        Thread.sleep(10);
        Properties.PropertiesChanged propertiesChangedSignal = getPropertiesChangeSignalDiscoveryStarted();
        central.handleSignal(propertiesChangedSignal);
        Thread.sleep(10);
        return central;
    }

    private BluetoothCentral startScanWithAddress(String peripheralAddress) throws InterruptedException, DBusException {
        when(bluezAdapter.isDiscovering()).thenReturn(false);
        when(bluezAdapter.isPowered()).thenReturn(true);
        BluetoothCentral central = new BluetoothCentral(callback, Collections.emptySet(), bluezAdapter);
        central.scanForPeripheralsWithAddresses(new String[]{peripheralAddress});
        Thread.sleep(10);
        Properties.PropertiesChanged propertiesChangedSignal = getPropertiesChangeSignalDiscoveryStarted();
        central.handleSignal(propertiesChangedSignal);
        Thread.sleep(10);
        return central;
    }

    private BluetoothCentral startScanWithNames(String peripheralName) throws InterruptedException, DBusException {
        when(bluezAdapter.isDiscovering()).thenReturn(false);
        when(bluezAdapter.isPowered()).thenReturn(true);
        BluetoothCentral central = new BluetoothCentral(callback, Collections.emptySet(), bluezAdapter);
        central.scanForPeripheralsWithNames(new String[]{peripheralName});
        Thread.sleep(10);
        Properties.PropertiesChanged propertiesChangedSignal = getPropertiesChangeSignalDiscoveryStarted();
        central.handleSignal(propertiesChangedSignal);
        Thread.sleep(10);
        return central;
    }
}
