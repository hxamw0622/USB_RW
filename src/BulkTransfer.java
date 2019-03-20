/*
 * Copyright (C) 2014 Klaus Reimer <k@ailis.de>
 * See LICENSE.txt for licensing information.
 */

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import java.nio.IntBuffer;

import org.usb4java.BufferUtils;
import org.usb4java.DeviceHandle;
import org.usb4java.LibUsb;
import org.usb4java.LibUsbException;

import org.usb4java.*;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Demonstrates how to do asynchronous bulk transfers. This demo sends some
 * hardcoded data to an Android Device (Samsung Galaxy Nexus) and receives some
 * data from it.
 * 
 * If you have a different Android device then you can get this demo working by
 * changing the vendor/product id, the interface number and the endpoint
 * addresses.
 * 
 * In this example the event handling is done with a thread which calls the
 * {@link LibUsb#handleEventsTimeout(Context, long)} method in a
 * loop. You could also run this command inside the main application loop if
 * there is one.
 * 
 * @author Klaus Reimer <k@ailis.de>
 */
public class BulkTransfer
{
    /**
     * This is the event handling thread. libusb doesn't start threads by its
     * own so it is our own responsibility to give libusb time to handle the
     * events in our own thread.
     */
    static class EventHandlingThread extends Thread
    {
        /** If thread should abort. */
        private volatile boolean abort;

        /**
         * Aborts the event handling thread.
         */
        public void abort()
        {
            this.abort = true;
        }

        @Override
        public void run()
        {
            while (!this.abort)
            {
                // Let libusb handle pending events. This blocks until events
                // have been handled, a hotplug callback has been deregistered
                // or the specified time of 0.5 seconds (Specified in
                // Microseconds) has passed.
                int result = LibUsb.handleEventsTimeout(null, 500000);
                if (result != LibUsb.SUCCESS)
                    throw new LibUsbException("Unable to handle events", result);
            }
        }
    }

    /** The vendor ID of STM32F407. */
    private static final short VENDOR_ID = 0x0483;

    /** The vendor ID of STM32F407. */
    private static final short PRODUCT_ID = 0x5740;

    /** The ADB interface number of STM32F407. */
    private static final byte INTERFACE = 1;

    /** The ADB input endpoint of STM32F407. */
    private static final byte IN_ENDPOINT = (byte) 0x81;

    /** The ADB output endpoint of STM32F407. */
    private static final byte OUT_ENDPOINT = 0x01;

    /** The communication timeout in milliseconds. */
    private static final int TIMEOUT = 5000;

    private static int dataSize =0;

    /**
     * Flag set during the asynchronous transfers to indicate the program is
     * finished.
     */
    static volatile boolean exit = false;


    /**
     * Searches for the missile launcher device and returns it. If there are
     * multiple missile launchers attached then this simple demo only returns
     * the first one.
     *
     * @return The missile launcher USB device or null if not found.
     */
    public static Device findUsbDevice()
    {
        // Read the USB device list
        DeviceList list = new DeviceList();
        int result = LibUsb.getDeviceList(null, list);
        if (result < 0)
        {
            throw new RuntimeException(
                    "Unable to get device list. Result=" + result);
        }

        try
        {
            // Iterate over all devices and scan for the missile launcher
            for (Device device: list)
            {
                DeviceDescriptor descriptor = new DeviceDescriptor();
                result = LibUsb.getDeviceDescriptor(device, descriptor);
                if (result < 0)
                {
                    throw new RuntimeException(
                            "Unable to read device descriptor. Result=" + result);
                }
                if (descriptor.idVendor() == VENDOR_ID
                        && descriptor.idProduct() == PRODUCT_ID) return device;
            }
        }
        finally
        {
            // Ensure the allocated device list is freed
            LibUsb.freeDeviceList(list, true);
        }

        // No missile launcher found
        return null;
    }

    /**
     * Asynchronously writes some data to the device.
     * 
     * @param handle
     *            The device handle.
     * @param data
     *            The data to send to the device.
     * @param callback
     *            The callback to execute when data has been transfered.
     */
    public static void write(DeviceHandle handle, byte[] data,
        TransferCallback callback)
    {
        ByteBuffer buffer = BufferUtils.allocateByteBuffer(data.length);
        buffer.put(data);
        Transfer transfer = LibUsb.allocTransfer();
        LibUsb.fillBulkTransfer(transfer, handle, OUT_ENDPOINT, buffer,
            callback, null, TIMEOUT);
        System.out.println("Sending " + data.length + " bytes to device");
        int result = LibUsb.submitTransfer(transfer);
        if (result != LibUsb.SUCCESS)
        {
            throw new LibUsbException("Unable to submit transfer", result);
        }
    }

    /**
     * Asynchronously reads some data from the device.
     * 
     * @param handle
     *            The device handle.
     * @param size
     *            The number of bytes to read from the device.
     * @param callback
     *            The callback to execute when data has been received.
     */
    public static void read(DeviceHandle handle, int size,
        TransferCallback callback)
    {
        ByteBuffer buffer = BufferUtils.allocateByteBuffer(size).order(
            ByteOrder.LITTLE_ENDIAN);
        Transfer transfer = LibUsb.allocTransfer();
        LibUsb.fillBulkTransfer(transfer, handle, IN_ENDPOINT, buffer,
            callback, null, TIMEOUT);
        System.out.println("Reading " + size + " bytes from device");
        int result = LibUsb.submitTransfer(transfer);
        if (result != LibUsb.SUCCESS)
        {
            throw new LibUsbException("Unable to submit transfer", result);
        }
    }


    /**
     * Writes some data to the device.
     *
     * @param handle
     *            The device handle.
     * @param data
     *            The data to send to the device.
     */
    public static void write(DeviceHandle handle, byte[] data)
    {
        ByteBuffer buffer = BufferUtils.allocateByteBuffer(data.length);
        buffer.put(data);
        IntBuffer transferred = BufferUtils.allocateIntBuffer();
        int result = LibUsb.bulkTransfer(handle, OUT_ENDPOINT, buffer,
                transferred, TIMEOUT);
        if (result != LibUsb.SUCCESS)
        {
            throw new LibUsbException("Unable to send data", result);
        }
        System.out.println(transferred.get() + " bytes sent to device");
    }

    /**
     * Reads some data from the device.
     *
     * @param handle
     *            The device handle.
     * @param size
     *            The number of bytes to read from the device.
     * @return The read data.
     */
    public static ByteBuffer read(DeviceHandle handle, int size)
    {
        int size_tmp;
        ByteBuffer buffer = BufferUtils.allocateByteBuffer(size).order(
                ByteOrder.LITTLE_ENDIAN);
        IntBuffer transferred = BufferUtils.allocateIntBuffer();
        int result = LibUsb.bulkTransfer(handle, IN_ENDPOINT, buffer,
                transferred, TIMEOUT);
        if (result != LibUsb.SUCCESS)
        {
            throw new LibUsbException("Unable to read data", result);
        }
        size_tmp = transferred.get();
        byte[] abytes = new byte[size_tmp];
        buffer.get(abytes);
        System.out.println(size_tmp + " bytes read from device");
        System.out.println("receive data "+new String(abytes));
        dataSize += size_tmp;
        return buffer;
    }

    /**
     * Main method.
     * 
     * @param args
     *            Command-line arguments (Ignored)
     * @throws Exception
     *             When something goes wrong.
     */
    public static void main(String[] args) throws Exception
    {
        // Initialize the libusb context
        int result = LibUsb.init(null);
        if (result != LibUsb.SUCCESS)
        {
            throw new LibUsbException("Unable to initialize libusb", result);
        }

        // Search for the USB device and stop when not found
        Device device = findUsbDevice();
        if (device == null)
        {
            System.err.println("USB Device not found.");
            System.exit(1);
        }

        // Open the device
        DeviceHandle handle = new DeviceHandle();
        result = LibUsb.open(device, handle);
        if (result != LibUsb.SUCCESS)
        {
            throw new LibUsbException("Unable to open USB device", result);
        }

        // Start event handling thread
        EventHandlingThread thread = new EventHandlingThread();
        thread.start();

        try {
            // Check if kernel driver is attached to the interface
            int attached = LibUsb.kernelDriverActive(handle, 1);
            if (attached < 0) {
                throw new LibUsbException(
                        "Unable to check kernel driver active", result);
            }

            // Detach kernel driver from interface 0 and 1. This can fail if
            // kernel is not attached to the device or operating system
            // doesn't support this operation. These cases are ignored here.
            result = LibUsb.detachKernelDriver(handle, 1);
            if (result != LibUsb.SUCCESS &&
                    result != LibUsb.ERROR_NOT_SUPPORTED &&
                    result != LibUsb.ERROR_NOT_FOUND) {
                throw new LibUsbException("Unable to detach kernel driver",
                        result);
            }

            // Claim the ADB interface
            result = LibUsb.claimInterface(handle, INTERFACE);
            if (result != LibUsb.SUCCESS) {
                throw new LibUsbException("Unable to claim interface", result);
            }

//            // This callback is called after the ADB answer body has been
//            // received. The asynchronous transfer chain ends here.
//            final TransferCallback bodyReceived = new TransferCallback() {
//                @Override
//                public void processTransfer(Transfer transfer) {
//                    System.out.println(transfer.actualLength() + " bytes received");
//                    LibUsb.freeTransfer(transfer);
//                    System.out.println("Asynchronous communication finished");
//                    exit = true;
//                }
//            };
//
//            // This callback is called after the ADB answer header has been
//            // received and reads the ADB answer body
//            final TransferCallback headerReceived = new TransferCallback() {
//                @Override
//                public void processTransfer(Transfer transfer) {
//                    System.out.println(transfer.actualLength() + " bytes received");
//                    ByteBuffer header = transfer.buffer();
//                    header.position(12);
//                    int dataSize = header.asIntBuffer().get();
//                    read(handle, dataSize, bodyReceived);
//                    LibUsb.freeTransfer(transfer);
//                }
//            };
//
//            // This callback is called after the ADB CONNECT message body is sent
//            // and starts reads the ADB answer header.
//            final TransferCallback bodySent = new TransferCallback() {
//                @Override
//                public void processTransfer(Transfer transfer) {
//                    System.out.println(transfer.actualLength() + " bytes received");
//                    read(handle, 24, headerReceived);
//                    // write(handle, CONNECT_BODY, receiveHeader);
//                    LibUsb.freeTransfer(transfer);
//                }
//            };
//
            // This callback is called after the ADB CONNECT message header is
            // sent and sends the ADB CONNECT message body.
//            final TransferCallback readCallback = new TransferCallback() {
//                @Override
//                public void processTransfer(Transfer transfer) {
//                    System.out.println(transfer.actualLength() + " bytes receive");
//                    LibUsb.freeTransfer(transfer);
//                    //exit = true;
//                }
//            };
//
//            // Send ADB CONNECT message header asynchronously. The rest of the
//            // communication is handled by the callbacks defined above.


            Runnable runnable = new Runnable() {
                public void run() {
                    // task to run goes here
                    System.out.println("The usb data speed is "+dataSize/2+"MB/s");
                    dataSize  = 0;
                }
            };
            ScheduledExecutorService service = Executors
                    .newSingleThreadScheduledExecutor();
            service.scheduleAtFixedRate(runnable, 0, 2, TimeUnit.SECONDS);


            ByteBuffer data;
            byte[] message = new byte[] { '1','2','3','4','5','6','7','8','9','0','a' };
            while(true)
            {
                write(handle,message);
                data  = read(handle, 1024);
            }

            // Fake application loop
//            while (!exit) {
//                Thread.yield();
//            }
//
//            // Release the ADB interface
//            result = LibUsb.releaseInterface(handle, INTERFACE);
//            if (result != LibUsb.SUCCESS) {
//                throw new LibUsbException("Unable to release interface", result);
//            }
//
//            // Re-attach kernel driver if needed
//            if (attached == 1)
//            {
//                LibUsb.attachKernelDriver(handle, 1);
//                if (result != LibUsb.SUCCESS)
//                {
//                    throw new LibUsbException(
//                            "Unable to re-attach kernel driver", result);
//                }
//            }
//
//            System.out.println("Exiting");
//
//            // Stop event handling thread
//            thread.abort();
//            thread.join();
        }

        finally
        {
            // Close the device
            LibUsb.close(handle);
            System.out.println("Program finished");
        }

        // Deinitialize the libusb context
       // LibUsb.exit(null);
    }
}
