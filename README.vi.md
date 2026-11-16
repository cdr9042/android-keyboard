# FUTO Keyboard – bản fork VNI

Đây là bản sửa đổi của [FUTO Keyboard](https://github.com/futo-org/android-keyboard), cải thiện gõ tiếng Việt kiểu VNI.

## Layout VNI (hàng dấu)

Layout mới "VNI (tone row)", nằm cạnh layout VNI cũ (layout cũ giữ nguyên) ([fork layouts](https://github.com/cdr9042/futo-keyboard-layouts)). Phím dấu vẫn gửi đúng mã VNI như trước, nhưng nhãn hiện dấu mà nó gõ:

| Phím | Dấu | Ví dụ |
|---|---|---|
| 1 | sắc | a1 → á |
| 2 | huyền | a2 → à |
| 3 | hỏi | a3 → ả |
| 4 | ngã | a4 → ã |
| 5 | nặng | a5 → ạ |
| 6 | mũ | a6 → â, e6 → ê, o6 → ô |
| 7 | móc | o7 → ơ, u7 → ư |
| 8 | trăng | a8 → ă |
| 9 | gạch | d9 → đ |

- Tắt hàng số (mặc định): một hàng hiện cả số lẫn dấu.
- Bật hàng số: hàng số thật gõ ra số, hàng dấu nằm ngay dưới và chỉ hiện dấu.

## Gõ vuốt hiểu dấu

Mô hình gõ vuốt chỉ biết chữ cái. Trước đây, vuốt qua hàng dấu thì các điểm đó bị đưa vào mô hình như nhiễu, còn phím dấu đã vuốt qua thì bị bỏ qua, nên luôn ra cách viết phổ biến nhất (`viet` ra từ nào hay gặp nhất). Giờ, khi layout có phím dấu VNI:

1. Chọn dấu. Phím dấu chỉ được tính khi ngón tay dừng trên nó: đi chậm hơn 0,005 bề rộng phím mỗi ms trong ít nhất 60 ms, hoặc nét vuốt bắt đầu hay kết thúc ở đó. Các phím dấu chỉ lướt qua (đi từ 6 sang 1 sẽ qua 5, 4, 3, 2) đi nhanh nên bị bỏ qua.
2. Cắt đường vuốt. Mỗi lần ngón tay lên hàng dấu bị cắt khỏi đường vuốt đưa vào mô hình, từ chữ cuối cùng ngón tay dừng trước đó tới chữ đầu tiên dừng sau đó. Các chữ chỉ lướt qua lúc đi lên và đi xuống không tới được mô hình.
3. Chữ chắc chắn đã gõ. Điểm đầu, điểm cuối, chỗ dừng (từ 60 ms) và chỗ đổi hướng gắt (từ 60 độ) là các chữ người dùng chắc chắn muốn gõ. Điểm nằm trên ranh giới hai phím được tính là phím nào cũng được. Từ gợi ý phải chứa đủ các chữ này theo đúng thứ tự, điểm đầu là chữ đầu, điểm cuối là chữ cuối. Từ thiếu chữ nào bị loại, trừ khi không từ nào đủ. Nhờ vậy một lần chậm lại thoáng qua ở chỗ rẽ, hay một phím chỉ lướt qua, không làm đổi từ (khi vuốt "thật", ngón tay chậm lại 34 ms ở chữ y giữa hai phím dấu, mô hình đọc thành t-h-a-y-t và gợi ý "thay").
4. Viết lại có dấu. Mô hình được hỏi 16 từ gợi ý thay vì 4. Phần chữ cái của mỗi từ còn được viết lại với đúng các dấu đã chọn (men -> mến, thiết -> thiệt), chỉ giữ khi từ đó có trong từ điển.
5. Xếp hạng lại. Đứng đầu là các từ mà mọi dấu đều nằm trong số dấu đã chọn, từ dùng nhiều dấu hơn đứng trước, rồi tới từ có đường vuốt khớp chữ cái hơn. Tiếp theo là từ không dấu, cuối cùng là từ có dấu không được chọn. Điểm cao nhất được gán theo thứ tự này để các bước sắp xếp sau vẫn giữ nguyên.

## Sửa dấu cho từ vừa vuốt

Từ gõ bằng vuốt, hoặc từ mà con trỏ quay lại, được nạp lại vào bộ gõ VNI dưới dạng phím VNI ("tháng" -> "tha1ng"). Bấm phím dấu sau đó vẫn thêm hoặc đổi được dấu cho từ đó.

---

Báo lỗi liên quan tới các thay đổi này ở [đây](https://github.com/cdr9042/android-keyboard/issues). Hướng dẫn build và các thông tin khác xem [README.md](README.md).

Giấy phép: [FUTO Source First License 1.1](LICENSE.md)