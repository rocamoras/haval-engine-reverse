package com.beantechs.weatherservice.remote;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/* loaded from: classes.dex */
public interface IWeatherCallBackListener extends IInterface {

    public static class Default implements IWeatherCallBackListener {
        @Override // android.os.IInterface
        public IBinder asBinder() {
            return null;
        }

        @Override // com.beantechs.weatherservice.remote.IWeatherCallBackListener
        public void onAlarmSuccess(String str) throws RemoteException {
        }

        @Override // com.beantechs.weatherservice.remote.IWeatherCallBackListener
        public void onAssociateWeatherWord(String str, String str2) throws RemoteException {
        }

        @Override // com.beantechs.weatherservice.remote.IWeatherCallBackListener
        public void onHourWeather(String str) throws RemoteException {
        }

        @Override // com.beantechs.weatherservice.remote.IWeatherCallBackListener
        public void onHourWeatherWithLoc(String str, String str2) throws RemoteException {
        }

        @Override // com.beantechs.weatherservice.remote.IWeatherCallBackListener
        public void onNowWeather(String str) throws RemoteException {
        }

        @Override // com.beantechs.weatherservice.remote.IWeatherCallBackListener
        public void onNowWeatherWithLoc(String str, String str2) throws RemoteException {
        }

        @Override // com.beantechs.weatherservice.remote.IWeatherCallBackListener
        public void onRecentWeather(String str) throws RemoteException {
        }

        @Override // com.beantechs.weatherservice.remote.IWeatherCallBackListener
        public void onRecentWeatherWithLoc(String str, String str2) throws RemoteException {
        }

        @Override // com.beantechs.weatherservice.remote.IWeatherCallBackListener
        public void onUnifiedWeather(CityInfoBean cityInfoBean, String str) throws RemoteException {
        }

        @Override // com.beantechs.weatherservice.remote.IWeatherCallBackListener
        public void onUnifiedWeatherWithLoc(CityInfoBean cityInfoBean, String str) throws RemoteException {
        }
    }

    public static abstract class Stub extends Binder implements IWeatherCallBackListener {
        private static final String DESCRIPTOR = "com.beantechs.weatherservice.remote.IWeatherCallBackListener";
        static final int TRANSACTION_onAlarmSuccess = 5;
        static final int TRANSACTION_onAssociateWeatherWord = 8;
        static final int TRANSACTION_onHourWeather = 7;
        static final int TRANSACTION_onHourWeatherWithLoc = 6;
        static final int TRANSACTION_onNowWeather = 2;
        static final int TRANSACTION_onNowWeatherWithLoc = 1;
        static final int TRANSACTION_onRecentWeather = 4;
        static final int TRANSACTION_onRecentWeatherWithLoc = 3;
        static final int TRANSACTION_onUnifiedWeather = 9;
        static final int TRANSACTION_onUnifiedWeatherWithLoc = 10;

        private static class Proxy implements IWeatherCallBackListener {
            public static IWeatherCallBackListener sDefaultImpl;
            private IBinder mRemote;

            Proxy(IBinder iBinder) {
                this.mRemote = iBinder;
            }

            @Override // android.os.IInterface
            public IBinder asBinder() {
                return this.mRemote;
            }

            public String getInterfaceDescriptor() {
                return Stub.DESCRIPTOR;
            }

            @Override // com.beantechs.weatherservice.remote.IWeatherCallBackListener
            public void onAlarmSuccess(String str) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeString(str);
                    if (this.mRemote.transact(5, obtain, obtain2, 0) || Stub.getDefaultImpl() == null) {
                        obtain2.readException();
                    } else {
                        Stub.getDefaultImpl().onAlarmSuccess(str);
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.beantechs.weatherservice.remote.IWeatherCallBackListener
            public void onAssociateWeatherWord(String str, String str2) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeString(str);
                    obtain.writeString(str2);
                    if (this.mRemote.transact(8, obtain, obtain2, 0) || Stub.getDefaultImpl() == null) {
                        obtain2.readException();
                    } else {
                        Stub.getDefaultImpl().onAssociateWeatherWord(str, str2);
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.beantechs.weatherservice.remote.IWeatherCallBackListener
            public void onHourWeather(String str) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeString(str);
                    if (this.mRemote.transact(7, obtain, obtain2, 0) || Stub.getDefaultImpl() == null) {
                        obtain2.readException();
                    } else {
                        Stub.getDefaultImpl().onHourWeather(str);
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.beantechs.weatherservice.remote.IWeatherCallBackListener
            public void onHourWeatherWithLoc(String str, String str2) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeString(str);
                    obtain.writeString(str2);
                    if (this.mRemote.transact(6, obtain, obtain2, 0) || Stub.getDefaultImpl() == null) {
                        obtain2.readException();
                    } else {
                        Stub.getDefaultImpl().onHourWeatherWithLoc(str, str2);
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.beantechs.weatherservice.remote.IWeatherCallBackListener
            public void onNowWeather(String str) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeString(str);
                    if (this.mRemote.transact(2, obtain, obtain2, 0) || Stub.getDefaultImpl() == null) {
                        obtain2.readException();
                    } else {
                        Stub.getDefaultImpl().onNowWeather(str);
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.beantechs.weatherservice.remote.IWeatherCallBackListener
            public void onNowWeatherWithLoc(String str, String str2) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeString(str);
                    obtain.writeString(str2);
                    if (this.mRemote.transact(1, obtain, obtain2, 0) || Stub.getDefaultImpl() == null) {
                        obtain2.readException();
                    } else {
                        Stub.getDefaultImpl().onNowWeatherWithLoc(str, str2);
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.beantechs.weatherservice.remote.IWeatherCallBackListener
            public void onRecentWeather(String str) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeString(str);
                    if (this.mRemote.transact(4, obtain, obtain2, 0) || Stub.getDefaultImpl() == null) {
                        obtain2.readException();
                    } else {
                        Stub.getDefaultImpl().onRecentWeather(str);
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.beantechs.weatherservice.remote.IWeatherCallBackListener
            public void onRecentWeatherWithLoc(String str, String str2) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeString(str);
                    obtain.writeString(str2);
                    if (this.mRemote.transact(3, obtain, obtain2, 0) || Stub.getDefaultImpl() == null) {
                        obtain2.readException();
                    } else {
                        Stub.getDefaultImpl().onRecentWeatherWithLoc(str, str2);
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.beantechs.weatherservice.remote.IWeatherCallBackListener
            public void onUnifiedWeather(CityInfoBean cityInfoBean, String str) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    if (cityInfoBean != null) {
                        obtain.writeInt(1);
                        cityInfoBean.writeToParcel(obtain, 0);
                    } else {
                        obtain.writeInt(0);
                    }
                    obtain.writeString(str);
                    if (this.mRemote.transact(9, obtain, obtain2, 0) || Stub.getDefaultImpl() == null) {
                        obtain2.readException();
                    } else {
                        Stub.getDefaultImpl().onUnifiedWeather(cityInfoBean, str);
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.beantechs.weatherservice.remote.IWeatherCallBackListener
            public void onUnifiedWeatherWithLoc(CityInfoBean cityInfoBean, String str) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    if (cityInfoBean != null) {
                        obtain.writeInt(1);
                        cityInfoBean.writeToParcel(obtain, 0);
                    } else {
                        obtain.writeInt(0);
                    }
                    obtain.writeString(str);
                    if (this.mRemote.transact(10, obtain, obtain2, 0) || Stub.getDefaultImpl() == null) {
                        obtain2.readException();
                    } else {
                        Stub.getDefaultImpl().onUnifiedWeatherWithLoc(cityInfoBean, str);
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }
        }

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static IWeatherCallBackListener asInterface(IBinder iBinder) {
            if (iBinder == null) {
                return null;
            }
            IInterface queryLocalInterface = iBinder.queryLocalInterface(DESCRIPTOR);
            return (queryLocalInterface == null || !(queryLocalInterface instanceof IWeatherCallBackListener)) ? new Proxy(iBinder) : (IWeatherCallBackListener) queryLocalInterface;
        }

        public static IWeatherCallBackListener getDefaultImpl() {
            return Proxy.sDefaultImpl;
        }

        public static boolean setDefaultImpl(IWeatherCallBackListener iWeatherCallBackListener) {
            if (Proxy.sDefaultImpl != null) {
                throw new IllegalStateException("setDefaultImpl() called twice");
            }
            if (iWeatherCallBackListener == null) {
                return false;
            }
            Proxy.sDefaultImpl = iWeatherCallBackListener;
            return true;
        }

        @Override // android.os.IInterface
        public IBinder asBinder() {
            return this;
        }

        @Override // android.os.Binder
        public boolean onTransact(int i, Parcel parcel, Parcel parcel2, int i2) throws RemoteException {
            if (i == 1598968902) {
                parcel2.writeString(DESCRIPTOR);
                return true;
            }
            switch (i) {
                case 1:
                    parcel.enforceInterface(DESCRIPTOR);
                    onNowWeatherWithLoc(parcel.readString(), parcel.readString());
                    parcel2.writeNoException();
                    return true;
                case 2:
                    parcel.enforceInterface(DESCRIPTOR);
                    onNowWeather(parcel.readString());
                    parcel2.writeNoException();
                    return true;
                case 3:
                    parcel.enforceInterface(DESCRIPTOR);
                    onRecentWeatherWithLoc(parcel.readString(), parcel.readString());
                    parcel2.writeNoException();
                    return true;
                case 4:
                    parcel.enforceInterface(DESCRIPTOR);
                    onRecentWeather(parcel.readString());
                    parcel2.writeNoException();
                    return true;
                case 5:
                    parcel.enforceInterface(DESCRIPTOR);
                    onAlarmSuccess(parcel.readString());
                    parcel2.writeNoException();
                    return true;
                case 6:
                    parcel.enforceInterface(DESCRIPTOR);
                    onHourWeatherWithLoc(parcel.readString(), parcel.readString());
                    parcel2.writeNoException();
                    return true;
                case 7:
                    parcel.enforceInterface(DESCRIPTOR);
                    onHourWeather(parcel.readString());
                    parcel2.writeNoException();
                    return true;
                case 8:
                    parcel.enforceInterface(DESCRIPTOR);
                    onAssociateWeatherWord(parcel.readString(), parcel.readString());
                    parcel2.writeNoException();
                    return true;
                case 9:
                    parcel.enforceInterface(DESCRIPTOR);
                    onUnifiedWeather(parcel.readInt() != 0 ? CityInfoBean.CREATOR.createFromParcel(parcel) : null, parcel.readString());
                    parcel2.writeNoException();
                    return true;
                case 10:
                    parcel.enforceInterface(DESCRIPTOR);
                    onUnifiedWeatherWithLoc(parcel.readInt() != 0 ? CityInfoBean.CREATOR.createFromParcel(parcel) : null, parcel.readString());
                    parcel2.writeNoException();
                    return true;
                default:
                    return super.onTransact(i, parcel, parcel2, i2);
            }
        }
    }

    void onAlarmSuccess(String str) throws RemoteException;

    void onAssociateWeatherWord(String str, String str2) throws RemoteException;

    void onHourWeather(String str) throws RemoteException;

    void onHourWeatherWithLoc(String str, String str2) throws RemoteException;

    void onNowWeather(String str) throws RemoteException;

    void onNowWeatherWithLoc(String str, String str2) throws RemoteException;

    void onRecentWeather(String str) throws RemoteException;

    void onRecentWeatherWithLoc(String str, String str2) throws RemoteException;

    void onUnifiedWeather(CityInfoBean cityInfoBean, String str) throws RemoteException;

    void onUnifiedWeatherWithLoc(CityInfoBean cityInfoBean, String str) throws RemoteException;
}
