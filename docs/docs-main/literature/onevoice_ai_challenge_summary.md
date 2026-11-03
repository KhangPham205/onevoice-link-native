# OneVoice AI Challenge - Tóm tắt bài toán

Nguồn: https://saigonaihub.com/OneVoiceAIChallenge

## 1. Tổng quan cuộc thi

**OneVoice AI Challenge - Edge AI for Voice Communication** là cuộc thi do **Saigon AI Hub** và **Qualcomm** tổ chức, diễn ra từ **tháng 5 đến tháng 11 năm 2026**.

Mục tiêu của cuộc thi là khuyến khích các đội thi xây dựng thế hệ thiết bị dịch thuật thời gian thực mới, hoạt động bằng **Edge AI**, có khả năng hỗ trợ giao tiếp song ngữ tự nhiên mà không phụ thuộc vào cloud hoặc kết nối Internet ổn định.

Thông điệp chính của cuộc thi:

> Xây dựng tương lai nơi ngôn ngữ không còn là rào cản trong giao tiếp và cơ hội nghề nghiệp.

## 2. Bài toán đặt ra

Trong nền kinh tế toàn cầu hiện nay, ngôn ngữ không chỉ là yếu tố văn hóa mà còn ảnh hưởng trực tiếp đến khả năng tiếp cận cơ hội việc làm, đào tạo kỹ năng, an toàn lao động và thăng tiến nghề nghiệp.

Ở nhiều môi trường làm việc như:

- Nhà máy
- Công trường xây dựng
- Trung tâm logistics
- Bệnh viện
- Ngành dịch vụ

người lao động đến từ nhiều quốc gia khác nhau phải phối hợp hằng ngày. Việc giao tiếp rõ ràng, nhanh chóng và chính xác giữa các ngôn ngữ trở thành điều kiện quan trọng để duy trì năng suất, đảm bảo an toàn và hỗ trợ chuyển giao kỹ năng.

Tuy nhiên, bất bình đẳng trong giao tiếp vẫn là một rào cản lớn nhưng thường bị ẩn đi.

### Các vấn đề thực tế

- Chỉ khoảng **5% lao động Việt Nam thành thạo tiếng Anh**, thấp hơn nhiều quốc gia Đông Nam Á không nói tiếng Anh.
- Khoảng **87% doanh nghiệp FDI yêu cầu nhân viên có năng lực tiếng Anh hoặc ngoại ngữ**.
- Để đạt mức thông thạo ngoại ngữ cần nhiều năm đào tạo, trong khi nhiều người lao động không có đủ thời gian hoặc điều kiện tài chính.
- Trong thực tế, người lao động thường phải dùng cử chỉ, ký hiệu hoặc giao tiếp pha trộn nhiều ngôn ngữ, dễ dẫn đến hiểu nhầm.
- Hiểu nhầm trong môi trường sản xuất, xây dựng, logistics hoặc y tế có thể gây ảnh hưởng đến năng suất, chất lượng công việc và an toàn lao động.
- Nhiều công cụ dịch thuật hiện nay phụ thuộc vào Internet ổn định hoặc xử lý trên cloud, trong khi điều kiện kết nối tại hiện trường không phải lúc nào cũng đảm bảo.

## 3. Bài toán cần giải quyết

Cuộc thi đặt ra yêu cầu thiết kế và phát triển một **thiết bị dịch thuật AI độc lập**, có thể chạy trực tiếp trên thiết bị bằng Edge AI.

Thiết bị cần hỗ trợ giao tiếp song ngữ tự nhiên trong thời gian thực, với độ trễ thấp, ngay cả trong các môi trường khó như:

- Nhiều tiếng ồn
- Người dùng đang bận tay
- Kết nối Internet yếu hoặc không ổn định
- Người dùng không có nhiều thời gian học cách sử dụng thiết bị

Nói ngắn gọn, bài toán cần giải quyết là:

> Làm thế nào để xây dựng một thiết bị dịch giọng nói thời gian thực, hoạt động trên thiết bị, không phụ thuộc cloud, đủ nhanh, đủ chính xác và đủ dễ dùng cho người lao động trong môi trường thực tế?

## 4. Yêu cầu giải pháp

Các đội thi cần thiết kế và tạo nguyên mẫu thiết bị kết hợp hai nhóm năng lực chính.

### 4.1. Edge AI models

Giải pháp cần sử dụng các mô hình AI chạy trực tiếp trên thiết bị, kết hợp các thành phần:

- Nhận dạng giọng nói
- Dịch máy
- Tổng hợp giọng nói
- Tối ưu tốc độ và độ chính xác
- Không phụ thuộc vào xử lý trên cloud

Ban tổ chức khuyến khích thí sinh sử dụng các mô hình từ **Qualcomm AI Hub**, nơi có nhiều mô hình đã được tối ưu trước, bao gồm lượng tử hóa và biên dịch để chạy hiệu quả trên nền tảng Snapdragon.

### 4.2. User-centered design

Thiết bị cần được thiết kế xoay quanh người dùng thực tế, đặc biệt là người lao động trong:

- Nhà máy
- Công trường
- Kho bãi
- Trung tâm logistics

Yêu cầu thiết kế bao gồm:

- Giao diện và tương tác trực quan
- Dễ sử dụng với ít hoặc không cần đào tạo
- Phù hợp với môi trường làm việc thực tế
- Hỗ trợ tình huống người dùng đang bận tay
- Hạn chế gián đoạn luồng giao tiếp tự nhiên

## 5. Định dạng thiết bị

Cuộc thi không giới hạn thiết bị ở một loại phần cứng cụ thể. Giải pháp có thể triển khai trên bất kỳ dạng thiết bị di động hoặc thiết bị cầm tay nào, miễn là đáp ứng mục tiêu dịch thuật giọng nói thời gian thực bằng Edge AI.

Một số hướng thiết bị có thể hình dung:

- Thiết bị cầm tay
- Thiết bị đeo
- Thiết bị gắn trên người
- Thiết bị chuyên dụng cho môi trường công nghiệp
- Ứng dụng chạy trên phần cứng di động có khả năng xử lý AI tại chỗ

## 6. Ngôn ngữ mục tiêu

Cuộc thi tập trung vào các cặp ngôn ngữ sau:

- **Tiếng Việt ↔ Tiếng Anh**
- **Tiếng Việt ↔ Tiếng Quan Thoại**
- **Tiếng Việt ↔ Tiếng Hàn**

## 7. Tiêu chí đánh giá

Giải pháp sẽ được đánh giá theo ba nhóm tiêu chí chính.

### 7.1. Innovation & Novelty - 25%

Đánh giá mức độ sáng tạo và mới mẻ của cách tiếp cận, bao gồm:

- Cách giải quyết rào cản ngôn ngữ
- Tính độc đáo của thiết kế
- Các tính năng bổ sung giúp tăng khả năng sử dụng trong đời sống thực
- Khả năng tạo ra phương thức giao tiếp mới, hiệu quả hơn

### 7.2. Technical Performance / Technical Excellence - 50%

Đây là tiêu chí quan trọng nhất, tập trung vào hiệu năng kỹ thuật của hệ thống chạy trên thiết bị, bao gồm:

- Độ chính xác dịch thuật
- Tốc độ xử lý
- Độ phản hồi
- Độ trễ thấp trong giao tiếp
- Độ ổn định khi vận hành
- Hiệu quả của pipeline nhận dạng giọng nói, dịch và tổng hợp giọng nói
- Khả năng hoạt động không phụ thuộc cloud

Các giá trị benchmark cụ thể sẽ được công bố trước giai đoạn nộp prototype.

### 7.3. Business Viability / Business Impact - 25%

Đánh giá khả năng sản phẩm có thể triển khai thực tế và thương mại hóa, bao gồm:

- Tính khả thi khi đưa vào môi trường làm việc thật
- Khả năng mở rộng
- Tiềm năng thương mại
- Khả năng phát triển thành sản phẩm hữu ích cho người lao động và tổ chức

## 8. Kết quả mong đợi từ đội thi

Các đội thi cần tiến tới một nguyên mẫu thiết bị hoạt động được, có thể trình diễn trong các kịch bản giao tiếp thực tế.

Nguyên mẫu nên chứng minh được:

- Người dùng có thể nói bằng một ngôn ngữ và người đối diện nhận được bản dịch gần như tức thời.
- Hệ thống xử lý được trên thiết bị, không cần gửi dữ liệu lên cloud.
- Thiết bị phản hồi nhanh, ít gián đoạn hội thoại.
- Thiết kế phù hợp với môi trường lao động có tiếng ồn hoặc thao tác tay hạn chế.
- Giải pháp có tiềm năng trở thành sản phẩm thực tế.

## 9. Đối tượng tham gia

Cuộc thi mở cho các nhóm trong hệ sinh thái AI toàn cầu, đặc biệt tập trung vào Việt Nam và châu Á.

Các đối tượng phù hợp gồm:

- Nhà nghiên cứu độc lập
- Kỹ sư AI và AI practitioners
- Startup giai đoạn đầu
- Nhóm sản phẩm nhỏ
- Sinh viên và nhóm nghiên cứu đại học

Điều kiện bổ sung:

- Giải pháp nộp thi phải là phát triển gốc cho cuộc thi.
- Không được nộp sản phẩm đã thương mại hóa tương tự đáng kể với bài dự thi.
- Sản phẩm đã có mặt trên thị trường, đang tạo doanh thu hoặc sản xuất hàng loạt không đủ điều kiện.
- Prototype giai đoạn đầu hoặc ý tưởng tiền thương mại hóa được chấp nhận.

## 10. Lộ trình cuộc thi

- **Tháng 5 - 6/2026:** Đăng ký đội thi.
- **Tháng 7/2026:** Nộp đặc tả kỹ thuật, kiến trúc hệ thống và thiết kế kỹ thuật.
- **Tháng 8 - 9/2026:** Nộp prototype, xây dựng thiết bị hoạt động và chuẩn bị kịch bản demo.
- **Tháng 10/2026:** Thử nghiệm thực địa và đánh giá trong các kịch bản giao tiếp thực tế.
- **Tháng 11/2026:** Chung kết và demo trực tiếp tại TP. Hồ Chí Minh, Việt Nam.

## 11. Tóm tắt ngắn gọn

OneVoice AI Challenge yêu cầu các đội thi xây dựng một thiết bị dịch giọng nói thời gian thực chạy bằng Edge AI. Bài toán xuất phát từ rào cản ngôn ngữ trong môi trường lao động quốc tế, nơi người lao động cần giao tiếp nhanh, chính xác và an toàn nhưng không phải lúc nào cũng có Internet hoặc đủ năng lực ngoại ngữ.

Giải pháp cần kết hợp nhận dạng giọng nói, dịch máy và tổng hợp giọng nói trực tiếp trên thiết bị, tối ưu cho độ trễ thấp, độ chính xác cao và trải nghiệm sử dụng đơn giản trong môi trường thực tế như nhà máy, công trường và logistics.
