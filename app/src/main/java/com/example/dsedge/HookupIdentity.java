package com.example.dsedge;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.util.Log;

/**
 * Steam Native Hook-up: everything Steam on Linux reads about a USB HID controller before it picks a driver --
 * vendor/product id, release number, name, serial, interface and the HID report descriptor -- taken from the
 * real USB device, so DroidDeck can describe a hidraw node that is indistinguishable from it.
 */
final class HookupIdentity {
    private static final String TAG = "SDL3Bridge";

    // Generic Desktop / Gamepad collection with 64-byte vendor in/out reports. Used for controllers that have
    // no HID descriptor of their own (Xbox controllers speak GIP on a vendor-class interface).
    private static final byte[] VENDOR_GAMEPAD_DESCRIPTOR = bytes(
        0x05, 0x01, 0x09, 0x05, 0xa1, 0x01, 0x06, 0x00, 0xff, 0x09, 0x01, 0x15, 0x00, 0x26, 0xff, 0x00,
        0x75, 0x08, 0x95, 0x40, 0x81, 0x02, 0x09, 0x02, 0x95, 0x40, 0x91, 0x02, 0xc0);

    private HookupIdentity() {}

    /** Report descriptors read before the raw open, by SDL hid path. */
    private static final java.util.Map<String, byte[]> prefetched = new java.util.concurrent.ConcurrentHashMap<>();

    static void prefetch(Context context, String path) {
        try {
            UsbManager um = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            UsbDevice dev = um == null ? null : um.getDeviceList().get(deviceName(path));
            int index = ifaceIndex(path);
            if (dev == null || index < 0 || index >= dev.getInterfaceCount()) return;
            UsbInterface f = dev.getInterface(index);
            if (f.getInterfaceClass() != UsbConstants.USB_CLASS_HID) return;
            byte[] d = readReportDescriptor(um, dev, f);
            if (d != null) prefetched.put(path, d);
            Log.i(TAG, "hookup: report descriptor of " + path + ": " + (d == null ? "unreadable" : d.length + " bytes"));
        } catch (RuntimeException e) {
            Log.w(TAG, "hookup: descriptor prefetch failed: " + e);
        }
    }

    private static String deviceName(String path) {
        String n = path;
        for (int i = 0; i < 3 && n.lastIndexOf('/') > 0; i++) n = n.substring(0, n.lastIndexOf('/'));
        return n;
    }

    private static int ifaceIndex(String path) {
        try { return Integer.parseInt(path.substring(path.lastIndexOf('/') + 1)); } catch (RuntimeException e) { return -1; }
    }

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    /** SDL's Android HID path is "<usb device name>/<vid hex>/<pid hex>/<interface index>". */
    static Bundle build(Context context, int slot, String path, int vid, int pid, int ifaceIndex) {
        Bundle b = new Bundle();
        b.putInt("slot", slot);
        b.putInt("vid", vid);
        b.putInt("pid", pid);
        b.putInt("bus", 3);
        String name = String.format("Controller %04x:%04x", vid, pid);
        String manufacturer = "", product = "", serial = "";
        int ifaceNumber = ifaceIndex, ifaceClass = UsbConstants.USB_CLASS_HID, ifaceSub = 0, ifaceProto = 0, version = 0x0100;
        byte[] descriptor = null;
        try {
            UsbManager um = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            UsbDevice dev = um == null ? null : um.getDeviceList().get(deviceName(path));
            if (dev != null) {
                if (ifaceIndex >= 0 && ifaceIndex < dev.getInterfaceCount()) {
                    UsbInterface f = dev.getInterface(ifaceIndex);
                    ifaceNumber = f.getId();
                    ifaceClass = f.getInterfaceClass();
                    ifaceSub = f.getInterfaceSubclass();
                    ifaceProto = f.getInterfaceProtocol();
                }
                try { manufacturer = nz(dev.getManufacturerName()); } catch (SecurityException ignored) { }
                try { product = nz(dev.getProductName()); } catch (SecurityException ignored) { }
                try { serial = nz(dev.getSerialNumber()); } catch (SecurityException ignored) { }
                version = parseBcd(dev.getVersion());
                descriptor = prefetched.remove(path);
                if (descriptor == null && ifaceClass == UsbConstants.USB_CLASS_HID && ifaceIndex >= 0 && ifaceIndex < dev.getInterfaceCount())
                    descriptor = readReportDescriptor(um, dev, dev.getInterface(ifaceIndex));
                String joined = (manufacturer.isEmpty() || product.startsWith(manufacturer)) ? product : manufacturer + " " + product;
                if (!joined.trim().isEmpty()) name = joined.trim();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "hookup: identity of " + path + " incomplete: " + e);
        }
        if (descriptor == null || descriptor.length == 0) descriptor = VENDOR_GAMEPAD_DESCRIPTOR;
        b.putInt("version", version);
        b.putInt("iface", ifaceNumber);
        b.putInt("ifclass", ifaceClass);
        b.putInt("ifsub", ifaceSub);
        b.putInt("ifproto", ifaceProto);
        b.putString("name", name);
        b.putString("manufacturer", manufacturer);
        b.putString("product", product.isEmpty() ? name : product);
        b.putString("serial", serial);
        b.putByteArray("desc", descriptor);
        Log.i(TAG, String.format("hookup: slot %d = %04x:%04x \"%s\" interface %d class %d, descriptor %d bytes",
            slot, vid, pid, name, ifaceNumber, ifaceClass, descriptor.length));
        return b;
    }

    private static String nz(String s) { return s == null ? "" : s.trim(); }

    /** "1.00" / "2.24" -> bcdDevice. */
    private static int parseBcd(String v) {
        try {
            if (v == null) return 0x0100;
            String[] p = v.split("\\.");
            int major = Integer.parseInt(p[0], 16), minor = p.length > 1 ? Integer.parseInt(p[1], 16) : 0;
            return ((major & 0xff) << 8) | (minor & 0xff);
        } catch (RuntimeException e) {
            return 0x0100;
        }
    }

    /** GET_DESCRIPTOR(HID report) on the interface, over a second connection. */
    private static byte[] readReportDescriptor(UsbManager um, UsbDevice dev, UsbInterface iface) {
        UsbDeviceConnection c = null;
        try {
            c = um.openDevice(dev);
            if (c == null) return null;
            byte[] buf = new byte[4096];
            int ifaceNumber = iface.getId();
            // Interface-recipient request (the standard one). If another connection holds the interface,
            // Android refuses it; claim it briefly (nobody holds it right before the raw open), and as a last
            // resort ask with device recipient, which most controllers answer the same way.
            int n = c.controlTransfer(0x81, 0x06, 0x2200, ifaceNumber, buf, buf.length, 500);
            if (n <= 0 && c.claimInterface(iface, false)) {
                n = c.controlTransfer(0x81, 0x06, 0x2200, ifaceNumber, buf, buf.length, 500);
                c.releaseInterface(iface);
            }
            if (n <= 0) n = c.controlTransfer(0x80, 0x06, 0x2200, ifaceNumber, buf, buf.length, 500);
            if (n <= 0) return null;
            byte[] out = new byte[n];
            System.arraycopy(buf, 0, out, 0, n);
            return out;
        } catch (RuntimeException e) {
            return null;
        } finally {
            if (c != null) c.close();
        }
    }
}
