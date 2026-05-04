# ScreenOffVN
Một ứng dụng Android để điều khiển màn hình Android của bạn hiển thị hoặc không hiển thị thông qua Shizuku. Điều này không giống như việc nhấn phím nguồn, vì các ứng dụng khác sẽ TIẾP TỤC hoạt động sau khi màn hình tắt.

# Cách hoạt động
Bằng cách phản chiếu "android.view.SurfaceControl" (tương tự như Scrcpy). Kiểm tra trong "SurfaceControl.java".

# Ghi công (Credits)
Dự án này sử dụng các thư viện và tài nguyên sau:
- **Material Design 3 (M3)**: Thông qua [Material Web Components](https://github.com/material-components/material-web) cho giao diện người dùng web.
- **QR Code Styling**: Thư viện [qr-code-styling](https://github.com/kozakdenys/qr-code-styling) để tạo mã QR phong cách Pixel.
- **Shizuku**: Được phát triển bởi [RikkaApps](https://github.com/RikkaApps/Shizuku) để thực hiện các lệnh hệ thống không cần Root.
- **Google Fonts**: Sử dụng font [Roboto Flex](https://fonts.google.com/specimen/Roboto+Flex) cho giao diện đồng nhất.
