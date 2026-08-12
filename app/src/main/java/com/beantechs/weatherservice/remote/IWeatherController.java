package com.beantechs.weatherservice.remote;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import com.beantechs.weatherservice.remote.IInterfaceAsBinder;
import com.beantechs.weatherservice.remote.IWeatherCallBackListener;

/* loaded from: classes.dex */
public interface IWeatherController extends IInterface {

    public static class Default implements IWeatherController {
        @Override // android.os.IInterface
        public IBinder asBinder() {
            return null;
        }

        @Override // com.beantechs.weatherservice.remote.IWeatherController
        public boolean isHaveCacheData(int i) throws RemoteException {
            return false;
        }

        @Override // com.beantechs.weatherservice.remote.IWeatherController
        public void queryAssociateWeatherWord(String str, String str2) throws RemoteException {
        }

        @Override // com.beantechs.weatherservice.remote.IWeatherController
        public void queryUnifiedWeatherInfo(String str, String str2, String str3) throws RemoteException {
        }

        @Override // com.beantechs.weatherservice.remote.IWeatherController
        public void register(String str, IInterfaceAsBinder iInterfaceAsBinder) throws RemoteException {
        }

        @Override // com.beantechs.weatherservice.remote.IWeatherController
        public void registerCallbackListener(String str, IWeatherCallBackListener iWeatherCallBackListener) throws RemoteException {
        }

        @Override // com.beantechs.weatherservice.remote.IWeatherController
        public void syncHourWeatherByLoc(String str, String str2) throws RemoteException {
        }

        @Override // com.beantechs.weatherservice.remote.IWeatherController
        public void syncNowWeatherByLoc(String str, String str2) throws RemoteException {
        }

        @Override // com.beantechs.weatherservice.remote.IWeatherController
        public void syncRecentWeatherByLoc(String str, String str2) throws RemoteException {
        }

        @Override // com.beantechs.weatherservice.remote.IWeatherController
        public void syncWeather(String str) throws RemoteException {
        }

        @Override // com.beantechs.weatherservice.remote.IWeatherController
        public void unregister(String str, IInterfaceAsBinder iInterfaceAsBinder) throws RemoteException {
        }

        @Override // com.beantechs.weatherservice.remote.IWeatherController
        public void unregisterCallbackListener(String str, IWeatherCallBackListener iWeatherCallBackListener) throws RemoteException {
        }
    }

    public static abstract class Stub extends Binder implements IWeatherController {
        private static final String DESCRIPTOR = "com.beantechs.weatherservice.remote.IWeatherController";
        static final int TRANSACTION_isHaveCacheData = 9;
        static final int TRANSACTION_queryAssociateWeatherWord = 10;
        static final int TRANSACTION_queryUnifiedWeatherInfo = 11;
        static final int TRANSACTION_register = 1;
        static final int TRANSACTION_registerCallbackListener = 3;
        static final int TRANSACTION_syncHourWeatherByLoc = 8;
        static final int TRANSACTION_syncNowWeatherByLoc = 7;
        static final int TRANSACTION_syncRecentWeatherByLoc = 6;
        static final int TRANSACTION_syncWeather = 5;
        static final int TRANSACTION_unregister = 2;
        static final int TRANSACTION_unregisterCallbackListener = 4;

        private static class Proxy implements IWeatherController {
            public static IWeatherController sDefaultImpl;
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

            @Override // com.beantechs.weatherservice.remote.IWeatherController
            public boolean isHaveCacheData(int i) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeInt(i);
                    if (!this.mRemote.transact(9, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        return Stub.getDefaultImpl().isHaveCacheData(i);
                    }
                    obtain2.readException();
                    return obtain2.readInt() != 0;
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.beantechs.weatherservice.remote.IWeatherController
            public void queryAssociateWeatherWord(String str, String str2) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeString(str);
                    obtain.writeString(str2);
                    if (this.mRemote.transact(10, obtain, obtain2, 0) || Stub.getDefaultImpl() == null) {
                        obtain2.readException();
                    } else {
                        Stub.getDefaultImpl().queryAssociateWeatherWord(str, str2);
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.beantechs.weatherservice.remote.IWeatherController
            public void queryUnifiedWeatherInfo(String str, String str2, String str3) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeString(str);
                    obtain.writeString(str2);
                    obtain.writeString(str3);
                    if (this.mRemote.transact(11, obtain, obtain2, 0) || Stub.getDefaultImpl() == null) {
                        obtain2.readException();
                    } else {
                        Stub.getDefaultImpl().queryUnifiedWeatherInfo(str, str2, str3);
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.beantechs.weatherservice.remote.IWeatherController
            public void register(String str, IInterfaceAsBinder iInterfaceAsBinder) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeString(str);
                    obtain.writeStrongBinder(iInterfaceAsBinder != null ? iInterfaceAsBinder.asBinder() : null);
                    if (this.mRemote.transact(1, obtain, obtain2, 0) || Stub.getDefaultImpl() == null) {
                        obtain2.readException();
                    } else {
                        Stub.getDefaultImpl().register(str, iInterfaceAsBinder);
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.beantechs.weatherservice.remote.IWeatherController
            public void registerCallbackListener(String str, IWeatherCallBackListener iWeatherCallBackListener) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeString(str);
                    obtain.writeStrongBinder(iWeatherCallBackListener != null ? iWeatherCallBackListener.asBinder() : null);
                    if (this.mRemote.transact(3, obtain, obtain2, 0) || Stub.getDefaultImpl() == null) {
                        obtain2.readException();
                    } else {
                        Stub.getDefaultImpl().registerCallbackListener(str, iWeatherCallBackListener);
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.beantechs.weatherservice.remote.IWeatherController
            public void syncHourWeatherByLoc(String str, String str2) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeString(str);
                    obtain.writeString(str2);
                    if (this.mRemote.transact(8, obtain, obtain2, 0) || Stub.getDefaultImpl() == null) {
                        obtain2.readException();
                    } else {
                        Stub.getDefaultImpl().syncHourWeatherByLoc(str, str2);
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.beantechs.weatherservice.remote.IWeatherController
            public void syncNowWeatherByLoc(String str, String str2) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeString(str);
                    obtain.writeString(str2);
                    if (this.mRemote.transact(7, obtain, obtain2, 0) || Stub.getDefaultImpl() == null) {
                        obtain2.readException();
                    } else {
                        Stub.getDefaultImpl().syncNowWeatherByLoc(str, str2);
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.beantechs.weatherservice.remote.IWeatherController
            public void syncRecentWeatherByLoc(String str, String str2) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeString(str);
                    obtain.writeString(str2);
                    if (this.mRemote.transact(6, obtain, obtain2, 0) || Stub.getDefaultImpl() == null) {
                        obtain2.readException();
                    } else {
                        Stub.getDefaultImpl().syncRecentWeatherByLoc(str, str2);
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.beantechs.weatherservice.remote.IWeatherController
            public void syncWeather(String str) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeString(str);
                    if (this.mRemote.transact(5, obtain, obtain2, 0) || Stub.getDefaultImpl() == null) {
                        obtain2.readException();
                    } else {
                        Stub.getDefaultImpl().syncWeather(str);
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.beantechs.weatherservice.remote.IWeatherController
            public void unregister(String str, IInterfaceAsBinder iInterfaceAsBinder) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeString(str);
                    obtain.writeStrongBinder(iInterfaceAsBinder != null ? iInterfaceAsBinder.asBinder() : null);
                    if (this.mRemote.transact(2, obtain, obtain2, 0) || Stub.getDefaultImpl() == null) {
                        obtain2.readException();
                    } else {
                        Stub.getDefaultImpl().unregister(str, iInterfaceAsBinder);
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.beantechs.weatherservice.remote.IWeatherController
            public void unregisterCallbackListener(String str, IWeatherCallBackListener iWeatherCallBackListener) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeString(str);
                    obtain.writeStrongBinder(iWeatherCallBackListener != null ? iWeatherCallBackListener.asBinder() : null);
                    if (this.mRemote.transact(4, obtain, obtain2, 0) || Stub.getDefaultImpl() == null) {
                        obtain2.readException();
                    } else {
                        Stub.getDefaultImpl().unregisterCallbackListener(str, iWeatherCallBackListener);
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

        public static IWeatherController asInterface(IBinder iBinder) {
            if (iBinder == null) {
                return null;
            }
            IInterface queryLocalInterface = iBinder.queryLocalInterface(DESCRIPTOR);
            return (queryLocalInterface == null || !(queryLocalInterface instanceof IWeatherController)) ? new Proxy(iBinder) : (IWeatherController) queryLocalInterface;
        }

        public static IWeatherController getDefaultImpl() {
            return Proxy.sDefaultImpl;
        }

        public static boolean setDefaultImpl(IWeatherController iWeatherController) {
            if (Proxy.sDefaultImpl != null) {
                throw new IllegalStateException("setDefaultImpl() called twice");
            }
            if (iWeatherController == null) {
                return false;
            }
            Proxy.sDefaultImpl = iWeatherController;
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
                    register(parcel.readString(), IInterfaceAsBinder.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                case 2:
                    parcel.enforceInterface(DESCRIPTOR);
                    unregister(parcel.readString(), IInterfaceAsBinder.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                case 3:
                    parcel.enforceInterface(DESCRIPTOR);
                    registerCallbackListener(parcel.readString(), IWeatherCallBackListener.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                case 4:
                    parcel.enforceInterface(DESCRIPTOR);
                    unregisterCallbackListener(parcel.readString(), IWeatherCallBackListener.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                case 5:
                    parcel.enforceInterface(DESCRIPTOR);
                    syncWeather(parcel.readString());
                    parcel2.writeNoException();
                    return true;
                case 6:
                    parcel.enforceInterface(DESCRIPTOR);
                    syncRecentWeatherByLoc(parcel.readString(), parcel.readString());
                    parcel2.writeNoException();
                    return true;
                case 7:
                    parcel.enforceInterface(DESCRIPTOR);
                    syncNowWeatherByLoc(parcel.readString(), parcel.readString());
                    parcel2.writeNoException();
                    return true;
                case 8:
                    parcel.enforceInterface(DESCRIPTOR);
                    syncHourWeatherByLoc(parcel.readString(), parcel.readString());
                    parcel2.writeNoException();
                    return true;
                case 9:
                    parcel.enforceInterface(DESCRIPTOR);
                    boolean isHaveCacheData = isHaveCacheData(parcel.readInt());
                    parcel2.writeNoException();
                    parcel2.writeInt(isHaveCacheData ? 1 : 0);
                    return true;
                case 10:
                    parcel.enforceInterface(DESCRIPTOR);
                    queryAssociateWeatherWord(parcel.readString(), parcel.readString());
                    parcel2.writeNoException();
                    return true;
                case 11:
                    parcel.enforceInterface(DESCRIPTOR);
                    queryUnifiedWeatherInfo(parcel.readString(), parcel.readString(), parcel.readString());
                    parcel2.writeNoException();
                    return true;
                default:
                    return super.onTransact(i, parcel, parcel2, i2);
            }
        }
    }

    boolean isHaveCacheData(int i) throws RemoteException;

    void queryAssociateWeatherWord(String str, String str2) throws RemoteException;

    void queryUnifiedWeatherInfo(String str, String str2, String str3) throws RemoteException;

    void register(String str, IInterfaceAsBinder iInterfaceAsBinder) throws RemoteException;

    void registerCallbackListener(String str, IWeatherCallBackListener iWeatherCallBackListener) throws RemoteException;

    void syncHourWeatherByLoc(String str, String str2) throws RemoteException;

    void syncNowWeatherByLoc(String str, String str2) throws RemoteException;

    void syncRecentWeatherByLoc(String str, String str2) throws RemoteException;

    void syncWeather(String str) throws RemoteException;

    void unregister(String str, IInterfaceAsBinder iInterfaceAsBinder) throws RemoteException;

    void unregisterCallbackListener(String str, IWeatherCallBackListener iWeatherCallBackListener) throws RemoteException;
}
