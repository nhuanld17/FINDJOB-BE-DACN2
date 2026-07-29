-- ============================================ --
--  V10: Seed initial job categories            --
--  Tham khảo từ các trang tuyển dụng lớn:      --
--  VietnamWorks, TopCV, CareerBuilder, LinkedIn --
-- ============================================ --

INSERT INTO categories (name, slug, description, created_at)
VALUES
    ('Công nghệ thông tin', 'cong-nghe-thong-tin', 'Lập trình, DevOps, QA, Database, Security, IT Helpdesk', now()),
    ('Kinh doanh / Bán hàng', 'kinh-doanh-ban-hang', 'Sales, Kinh doanh, Phát triển thị trường, Telesales', now()),
    ('Marketing / Truyền thông', 'marketing-truyen-thong', 'Digital Marketing, Content, PR, Brand, Advertising, SEO', now()),
    ('Tài chính / Kế toán', 'tai-chinh-ke-toan', 'Kế toán, Kiểm toán, Phân tích tài chính, Thuế, Treasury', now()),
    ('Nhân sự / Hành chính', 'nhan-su-hanh-chinh', 'HR, Tuyển dụng, C&B, Đào tạo, Hành chính văn phòng', now()),
    ('Sản xuất / Vận hành', 'san-xuat-van-hanh', 'Quản lý sản xuất, Supply Chain, Vận hành nhà máy', now()),
    ('Xây dựng / Kiến trúc', 'xay-dung-kien-truc', 'Kỹ sư xây dựng, Kiến trúc sư, Thiết kế nội thất, Giám sát', now()),
    ('Giáo dục / Đào tạo', 'giao-duc-dao-tao', 'Giảng viên, Giáo viên, Đào tạo nội bộ, Tư vấn giáo dục', now()),
    ('Y tế / Dược phẩm', 'y-te-duoc-pham', 'Bác sĩ, Điều dưỡng, Dược sĩ, Nghiên cứu lâm sàng', now()),
    ('Logistics / Xuất nhập khẩu', 'logistics-xuat-nhap-khau', 'Kho vận, Xuất nhập khẩu, Chuỗi cung ứng, Hải quan', now()),
    ('Bất động sản', 'bat-dong-san', 'Môi giới, Quản lý dự án, Định giá, Phát triển bất động sản', now()),
    ('Nhà hàng / Khách sạn / Du lịch', 'nha-hang-khach-san-du-lich', 'F&B, Quản lý khách sạn, Lữ hành, Hướng dẫn viên', now()),
    ('Ngân hàng / Chứng khoán', 'ngan-hang-chung-khoan', 'Giao dịch viên, Tín dụng, Môi giới chứng khoán, Phân tích đầu tư', now()),
    ('Thiết kế / Mỹ thuật', 'thiet-ke-my-thuat', 'UI/UX Design, Đồ họa, Video, 3D, Photography, Kiểu dáng', now()),
    ('Pháp lý / Luật sư', 'phap-ly-luat-su', 'Luật sư, Pháp chế, Sở hữu trí tuệ, Hợp đồng', now()),
    ('Bán lẻ / Tiêu dùng', 'ban-le-tieu-dung', 'Quản lý cửa hàng, Merchandising, Retail Operations', now()),
    ('Dịch vụ khách hàng', 'dich-vu-khach-hang', 'CSKH, Chăm sóc khách hàng, Hotline, Hỗ trợ', now()),
    ('Bảo hiểm', 'bao-hiem', 'Tư vấn bảo hiểm, Thẩm định, Bồi thường, Đại lý', now()),
    ('Viễn thông / Network', 'vien-thong-network', 'Mạng, Hạ tầng, Telecom, ISP, Data Center', now()),
    ('Năng lượng / Môi trường', 'nang-luong-moi-truong', 'Điện, Dầu khí, Năng lượng tái tạo, Xử lý môi trường', now()),
    ('Cơ khí / Ô tô', 'co-khi-o-to', 'Kỹ sư cơ khí, Thiết kế máy, Bảo trì, Sản xuất ô tô', now()),
    ('Dệt may / Da giày', 'det-may-da-giay', 'Sản xuất may mặc, Thiết kế thời trang, Nguyên phụ liệu', now()),
    ('Nông nghiệp / Lâm nghiệp', 'nong-nghiep-lam-nghiep', 'Trồng trọt, Chăn nuôi, Chế biến nông sản, Lâm nghiệp', now()),
    ('Báo chí / Biên tập', 'bao-chi-bien-tap', 'Phóng viên, Biên tập viên, Truyền hình, Sản xuất nội dung', now()),
    ('Thương mại điện tử', 'thuong-mai-dien-tu', 'E-commerce, Online Sales, Marketplace, Dropshipping', now()),
    ('Hàng không / Vận tải', 'hang-khong-van-tai', 'Phi công, Tiếp viên, Kỹ thuật hàng không, Vận tải hàng hóa', now()),
    ('Thực phẩm / Đồ uống', 'thuc-pham-do-uong', 'Chế biến thực phẩm, An toàn vệ sinh, Quản lý chất lượng, F&B', now()),
    ('Chứng khoán / Đầu tư', 'chung-khoan-dau-tu', 'Phân tích đầu tư, Môi giới, Quản lý quỹ, Private Equity', now()),
    ('Quan hệ công chúng / PR', 'quan-he-cong-chung-pr', 'PR, Event, Tổ chức sự kiện, Media Relations, Influencer', now()),
    ('Dịch vụ khác', 'dich-vu-khac', 'Các ngành nghề dịch vụ khác không thuộc danh mục trên', now())
ON CONFLICT (slug) DO NOTHING;
