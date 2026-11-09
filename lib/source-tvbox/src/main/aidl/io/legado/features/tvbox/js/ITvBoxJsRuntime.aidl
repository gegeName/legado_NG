package io.legado.features.tvbox.js;
import android.os.ParcelFileDescriptor;
import io.legado.features.tvbox.js.ITvBoxJsHost;
interface ITvBoxJsRuntime {
    ParcelFileDescriptor open(String session, in ParcelFileDescriptor program, ITvBoxJsHost host, long timeout);
    ParcelFileDescriptor evaluate(String session, in ParcelFileDescriptor expression, long timeout);
    void release(String session);
    void abort();
}
