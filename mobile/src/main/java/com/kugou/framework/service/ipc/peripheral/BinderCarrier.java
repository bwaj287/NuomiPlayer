package com.kugou.framework.service.ipc.peripheral;

import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.Parcelable;

public final class BinderCarrier implements Parcelable {

    public static final Creator<BinderCarrier> CREATOR = new Creator<BinderCarrier>() {
        @Override
        public BinderCarrier createFromParcel(Parcel in) {
            return new BinderCarrier(in);
        }

        @Override
        public BinderCarrier[] newArray(int size) {
            return new BinderCarrier[size];
        }
    };

    private final IBinder binder;

    public BinderCarrier(IInterface iface) {
        this(iface != null ? iface.asBinder() : null);
    }

    public BinderCarrier(IBinder binder) {
        this.binder = binder;
    }

    private BinderCarrier(Parcel in) {
        binder = in.readStrongBinder();
    }

    public IBinder getBinder() {
        return binder;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStrongBinder(binder);
    }
}
