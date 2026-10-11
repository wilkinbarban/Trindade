package com.trindade.app.export;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import java.io.InputStream;

public class PdfGrantRecipientActivity extends Activity {
    public static final String EXTRA_CALLBACK = "com.trindade.app.export.extra.CALLBACK";
    public static final String EXTRA_NONCE = "com.trindade.app.export.extra.NONCE";
    public static final String EXTRA_MODE = "com.trindade.app.export.extra.MODE";
    public static final int MODE_IDENTITY = 0, MODE_PDF = 1;
    public static final int TRANSACTION_REPORT_IDENTITY = IBinder.FIRST_CALL_TRANSACTION;
    public static final int TRANSACTION_REPORT_PDF = IBinder.FIRST_CALL_TRANSACTION + 1;
    public static final String RESULT_READ_OK = "READ_OK", RESULT_SECURITY_DENIED = "SECURITY_DENIED", RESULT_UNEXPECTED_IO = "UNEXPECTED_IO";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle extras = getIntent() != null ? getIntent().getExtras() : null;
        IBinder binder = extras != null ? extras.getBinder(EXTRA_CALLBACK) : null;
        if (binder != null) {
            String nonce = extras.getString(EXTRA_NONCE);
            int mode = extras.getInt(EXTRA_MODE, MODE_IDENTITY);
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeString(nonce != null ? nonce : "");
                data.writeInt(Process.myUid());
                data.writeInt(Process.myPid());
                if (mode == MODE_PDF) {
                    executePdfRead(data);
                    binder.transact(TRANSACTION_REPORT_PDF, data, reply, 0);
                } else {
                    binder.transact(TRANSACTION_REPORT_IDENTITY, data, reply, 0);
                }
            } catch (Exception e) {
                PdfGrantRecipientActivity.<RuntimeException>sneakyThrow(e);
            } finally {
                data.recycle();
                reply.recycle();
            }
        }
        finish();
    }

    private void executePdfRead(Parcel data) {
        Uri uri = extractUri();
        String result = RESULT_UNEXPECTED_IO, exClass = "";
        byte[] prefix = new byte[0];
        if (uri == null) {
            exClass = "NullUriException";
        } else {
            ParcelFileDescriptor pfd = null;
            try {
                pfd = getContentResolver().openFileDescriptor(uri, "r");
            } catch (SecurityException se) {
                result = RESULT_SECURITY_DENIED;
                exClass = se.getClass().getName();
            } catch (Exception e) {
                exClass = e.getClass().getName();
            }
            if (pfd != null) {
                try {
                    prefix = readPrefix(pfd);
                    result = RESULT_READ_OK;
                } catch (Exception e) {
                    exClass = e.getClass().getName();
                }
            } else if (exClass.isEmpty()) {
                exClass = "NullPfdException";
            }
        }
        data.writeString(result);
        data.writeString(exClass);
        data.writeInt(prefix.length);
        data.writeByteArray(prefix);
    }

    private Uri extractUri() {
        ClipData clip = getIntent().getClipData();
        if (clip != null && clip.getItemCount() > 0) return clip.getItemAt(0).getUri();
        Bundle extras = getIntent().getExtras();
        return extras != null ? extras.getParcelable(Intent.EXTRA_STREAM) : null;
    }

    private byte[] readPrefix(ParcelFileDescriptor pfd) throws Exception {
        try (InputStream is = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            byte[] buf = new byte[4096];
            int total = 0, r;
            while (total < buf.length && (r = is.read(buf, total, buf.length - total)) > 0) total += r;
            byte[] prefix = new byte[total];
            System.arraycopy(buf, 0, prefix, 0, total);
            return prefix;
        }
    }

    private static <E extends Throwable> void sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }
}
