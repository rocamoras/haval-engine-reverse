package com.beantechs.weatherservice.remote;

import android.os.Parcel;
import android.os.Parcelable;

/* loaded from: classes.dex */
public class CityInfoBean implements Parcelable {
    public static final Parcelable.Creator<CityInfoBean> CREATOR = new Parcelable.Creator<CityInfoBean>() { // from class: com.beantechs.weatherservice.remote.CityInfoBean.1
        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public CityInfoBean createFromParcel(Parcel parcel) {
            return new CityInfoBean(parcel);
        }

        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public CityInfoBean[] newArray(int i) {
            return new CityInfoBean[i];
        }
    };
    private String areaCode;
    private String cityCode;
    private String cityName;
    private String districtName;
    private String provinceName;

    public CityInfoBean() {
    }

    protected CityInfoBean(Parcel parcel) {
        this.cityCode = parcel.readString();
        this.areaCode = parcel.readString();
        this.cityName = parcel.readString();
        this.districtName = parcel.readString();
        this.provinceName = parcel.readString();
    }

    @Override // android.os.Parcelable
    public int describeContents() {
        return 0;
    }

    public String getAreaCode() {
        return this.areaCode;
    }

    public String getCityCode() {
        return this.cityCode;
    }

    public String getCityName() {
        return this.cityName;
    }

    public String getDistrictName() {
        return this.districtName;
    }

    public String getProvinceName() {
        return this.provinceName;
    }

    public void readFromParcel(Parcel parcel) {
        this.cityCode = parcel.readString();
        this.areaCode = parcel.readString();
        this.cityName = parcel.readString();
        this.districtName = parcel.readString();
        this.provinceName = parcel.readString();
    }

    public void setAreaCode(String str) {
        this.areaCode = str;
    }

    public void setCityCode(String str) {
        this.cityCode = str;
    }

    public void setCityName(String str) {
        this.cityName = str;
    }

    public void setDistrictName(String str) {
        this.districtName = str;
    }

    public void setProvinceName(String str) {
        this.provinceName = str;
    }

    public String toString() {
        return "CityInfoBean{cityCode='" + this.cityCode + "', areaCode='" + this.areaCode + "', cityName='" + this.cityName + "', districtName='" + this.districtName + "', provinceName='" + this.provinceName + "'}";
    }

    @Override // android.os.Parcelable
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeString(this.cityCode);
        parcel.writeString(this.areaCode);
        parcel.writeString(this.cityName);
        parcel.writeString(this.districtName);
        parcel.writeString(this.provinceName);
    }
}
