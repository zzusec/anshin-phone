package com.anshin.phone;
import android.os.ParcelFileDescriptor;
interface IAuthorizedCleanup {
    String uninstall(String packageName) = 0;
    String restore(String packageName) = 1;
    int protocolVersion() = 2;
    String installBackup(String packageName, in ParcelFileDescriptor[] files, in String[] names, in long[] sizes) = 3;
    void destroy() = 16777114;
}
