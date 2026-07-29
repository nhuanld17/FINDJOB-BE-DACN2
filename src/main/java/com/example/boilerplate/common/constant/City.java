package com.example.boilerplate.common.constant;

/**
 * Danh sách 63 tỉnh/thành phố Việt Nam, sắp xếp theo thứ tự A-Z (theo tên tiếng Việt).
 * Dùng {@link #name()} để lưu xuống DB và giao tiếp qua API.
 * Dùng {@link #getDisplayName()} để hiển thị trên UI.
 */
public enum City {

    AN_GIANG("An Giang"),
    BA_RIA_VUNG_TAU("Bà Rịa - Vũng Tàu"),
    BAC_LIEU("Bạc Liêu"),
    BAC_GIANG("Bắc Giang"),
    BAC_KAN("Bắc Kạn"),
    BAC_NINH("Bắc Ninh"),
    BEN_TRE("Bến Tre"),
    BINH_DUONG("Bình Dương"),
    BINH_DINH("Bình Định"),
    BINH_PHUOC("Bình Phước"),
    BINH_THUAN("Bình Thuận"),
    CA_MAU("Cà Mau"),
    CAO_BANG("Cao Bằng"),
    CAN_THO("Cần Thơ"),
    DA_NANG("Đà Nẵng"),
    DAK_LAK("Đắk Lắk"),
    DAK_NONG("Đắk Nông"),
    DIEN_BIEN("Điện Biên"),
    DONG_NAI("Đồng Nai"),
    DONG_THAP("Đồng Tháp"),
    GIA_LAI("Gia Lai"),
    HA_GIANG("Hà Giang"),
    HA_NAM("Hà Nam"),
    HA_NOI("Hà Nội"),
    HA_TINH("Hà Tĩnh"),
    HAI_DUONG("Hải Dương"),
    HAI_PHONG("Hải Phòng"),
    HAU_GIANG("Hậu Giang"),
    HOA_BINH("Hòa Bình"),
    HO_CHI_MINH("Hồ Chí Minh"),
    HUNG_YEN("Hưng Yên"),
    KHANH_HOA("Khánh Hòa"),
    KIEN_GIANG("Kiên Giang"),
    KON_TUM("Kon Tum"),
    LAI_CHAU("Lai Châu"),
    LANG_SON("Lạng Sơn"),
    LAO_CAI("Lào Cai"),
    LAM_DONG("Lâm Đồng"),
    LONG_AN("Long An"),
    NAM_DINH("Nam Định"),
    NGHE_AN("Nghệ An"),
    NINH_BINH("Ninh Bình"),
    NINH_THUAN("Ninh Thuận"),
    PHU_THO("Phú Thọ"),
    PHU_YEN("Phú Yên"),
    QUANG_BINH("Quảng Bình"),
    QUANG_NAM("Quảng Nam"),
    QUANG_NGAI("Quảng Ngãi"),
    QUANG_NINH("Quảng Ninh"),
    QUANG_TRI("Quảng Trị"),
    SOC_TRANG("Sóc Trăng"),
    SON_LA("Sơn La"),
    TAY_NINH("Tây Ninh"),
    THAI_BINH("Thái Bình"),
    THAI_NGUYEN("Thái Nguyên"),
    THANH_HOA("Thanh Hóa"),
    THUA_THIEN_HUE("Thừa Thiên Huế"),
    TIEN_GIANG("Tiền Giang"),
    TRA_VINH("Trà Vinh"),
    TUYEN_QUANG("Tuyên Quang"),
    VINH_LONG("Vĩnh Long"),
    VINH_PHUC("Vĩnh Phúc"),
    YEN_BAI("Yên Bái");

    private final String displayName;

    City(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
