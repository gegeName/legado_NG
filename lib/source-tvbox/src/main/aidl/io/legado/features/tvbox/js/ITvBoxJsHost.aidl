package io.legado.features.tvbox.js;
import android.os.ParcelFileDescriptor;
interface ITvBoxJsHost {
    ParcelFileDescriptor call(String method, String arguments);
}
