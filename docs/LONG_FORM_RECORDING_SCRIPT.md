# Kịch bản thu âm lượt nói dài

Tài liệu này được tạo từ `tools/eval/longturn/recording_scripts.py`; muốn sửa văn bản thì sửa file đó rồi chạy lại `python3 tools/eval/longturn/recording_scripts.py render`.

## Mục đích

Các bản ghi này kiểm tra chế độ lượt nói dài (`longTurnMode=ADAPTIVE`) bằng giọng thật thay vì ghép câu tổng hợp. Mỗi file là **một lượt nói liên tục** của một người. Văn bản dưới đây là đáp án ASR, nên hãy đọc đúng chữ; nếu lỡ đọc sai một từ thì cứ đọc tiếp, đừng dừng để sửa, rồi ghi chú lại (xem phần Ghi chú).

## Cách thu

1. **Thiết bị:** thu bằng chính điện thoại thử nghiệm (micro trong máy), cầm cách miệng khoảng 30 cm như khi dùng route Phone. Dùng ứng dụng ghi âm có sẵn; định dạng nào cũng được, bước chuẩn bị sẽ chuyển sang WAV 16 kHz mono.
2. **Phòng:** phòng bình thường, đóng cửa. Chỉ các bài ghi `noise` mới bật quạt hoặc TV.
3. **Bắt đầu và kết thúc:** bấm ghi, im lặng khoảng 1 giây, đọc, rồi im lặng 2 giây mới bấm dừng.
4. **Khoảng nghỉ:** ứng dụng coi im lặng từ 1 giây trở lên là hết lượt. Nghỉ lấy hơi bình thường thì được, nhưng **đừng dừng quá khoảng nửa giây** giữa các câu. Bài `continuous` thì đọc một mạch.
5. **Đặt tên file:** đúng mã bài, ví dụ `vi05.m4a`, `en11.wav`. Thu lại một bài thì đặt `vi05_take2.m4a`; bước chuẩn bị sẽ lấy take mới nhất.
6. **Thời lượng:** con số 30/60/120 giây chỉ là ước lượng. Đừng cố đọc cho khớp; thời lượng thật được đo từ file.

## Sau khi thu

Chép toàn bộ file vào một thư mục, ví dụ `~/onevoice-recordings/`, rồi chạy:

```bash
python3 tools/eval/longturn/recording_scripts.py prepare --recordings ~/onevoice-recordings --out eval-results/longturn-real --speaker khang
python3 tools/eval/longturn/run_long_replay.py --serial <serial> --fixtures eval-results/longturn-real --out eval-results/longturn-real-run1 --baselines B0 B1 B2 B3 --mode paced --init
python3 tools/eval/longturn/score_long_replay.py eval-results/longturn-real-run1
```

Bản ghi chứa giọng nói cá nhân và bị git ignore (`eval-results/`). Đừng commit chúng.

## Ghi chú

Nếu bạn đọc sai so với văn bản, ghi vào file `notes.txt` trong thư mục bản ghi, mỗi dòng một bài, ví dụ `vi05: đọc "ba trăm" thành "bốn trăm"`. Bước chấm điểm sẽ tính phần chênh này là lỗi ASR nếu không có ghi chú.

## Phân bổ

| Mã | Ngôn ngữ | ~Giây | Điều kiện | Tập |
|---|---|---:|---|---|
| vi01 | VI | 30 | normal | tuning |
| vi02 | VI | 30 | normal | tuning |
| vi03 | VI | 30 | fast | tuning |
| vi04 | VI | 30 | soft | heldout |
| vi05 | VI | 60 | normal | tuning |
| vi06 | VI | 60 | normal | heldout |
| vi07 | VI | 60 | noise | tuning |
| vi08 | VI | 60 | continuous | heldout |
| vi09 | VI | 120 | normal | tuning |
| vi10 | VI | 120 | normal | tuning |
| vi11 | VI | 120 | mixed | tuning |
| vi12 | VI | 120 | noise | heldout |
| en01 | EN | 30 | normal | tuning |
| en02 | EN | 30 | normal | tuning |
| en03 | EN | 30 | fast | tuning |
| en04 | EN | 30 | soft | heldout |
| en05 | EN | 60 | normal | tuning |
| en06 | EN | 60 | normal | heldout |
| en07 | EN | 60 | noise | tuning |
| en08 | EN | 60 | continuous | heldout |
| en09 | EN | 120 | normal | tuning |
| en10 | EN | 120 | normal | tuning |
| en11 | EN | 120 | mixed | tuning |
| en12 | EN | 120 | noise | heldout |

`tuning` dùng để chỉnh tham số; `heldout` chỉ dùng để báo kết quả cuối.

### Điều kiện

- **normal**: Bình thường: giọng nói chuyện tự nhiên, tốc độ vừa phải.
- **fast**: Nhanh: nói nhanh hơn bình thường như khi đang vội, nhưng vẫn rõ chữ.
- **soft**: Nhỏ: nói nhỏ như trong phòng có người đang ngủ, không thì thầm.
- **continuous**: Liền mạch: đọc một mạch, không dừng giữa các câu (lấy hơi thật nhanh).
- **noise**: Có tiếng ồn: bật quạt hoặc TV ở mức vừa, cách điện thoại khoảng 2 m.
- **mixed**: Đổi nhịp: đoạn đầu nói chậm, đoạn giữa nhanh, đoạn cuối bình thường.

## Tiếng Việt

### vi01 · ~30 s · normal · tuning

*Bình thường: giọng nói chuyện tự nhiên, tốc độ vừa phải.*

Tôi chỉ đồng ý triển khai phương án này nếu tất cả điều kiện sau được đáp ứng. Thứ nhất, ngân sách không được vượt quá hai trăm năm mươi triệu đồng. Thứ hai, đội kỹ thuật phải hoàn thành bản thử nghiệm trước ngày mười lăm tháng mười hai. Thứ ba, khách hàng phải ký xác nhận bằng văn bản. Nếu thiếu bất kỳ điều kiện nào, chúng ta sẽ tạm dừng và họp lại vào thứ hai tuần sau để xem xét phương án khác. Tôi cũng đề nghị anh Hùng chuẩn bị bảng so sánh chi phí giữa hai nhà cung cấp, và gửi cho cả nhóm trước buổi họp ít nhất một ngày, để mọi người có thời gian đọc kỹ.

### vi02 · ~30 s · normal · tuning

*Bình thường: giọng nói chuyện tự nhiên, tốc độ vừa phải.*

Từ khách sạn, anh đi thẳng khoảng năm trăm mét rồi rẽ trái ở ngã tư thứ hai. Đi tiếp chừng hai cây số nữa sẽ thấy cầu Rồng ở bên phải. Đừng đi qua cầu, mà hãy quẹo vào đường Bạch Đằng. Nhà hàng nằm ở số một trăm hai mươi ba, mở cửa từ sáu giờ sáng đến mười giờ tối, trừ chủ nhật. Nếu lạc đường thì anh gọi cho tôi theo số không chín không bốn, một hai ba, bốn năm sáu. Gần nhà hàng có một bãi giữ xe máy, giá năm nghìn đồng một lượt. Nếu anh đi taxi thì cứ nói với tài xế là chở tới chợ đêm Sơn Trà, từ đó đi bộ thêm khoảng hai trăm mét là tới.

### vi03 · ~30 s · fast · tuning

*Nhanh: nói nhanh hơn bình thường như khi đang vội, nhưng vẫn rõ chữ.*

Máy tính của em bị treo từ sáng tới giờ, bấm nút nguồn cũng không tắt được. Em đã thử rút sạc, đợi khoảng ba phút rồi cắm lại nhưng vẫn không được. Màn hình chỉ hiện logo rồi tắt. Anh xem giúp em là lỗi phần cứng hay phần mềm, có cần mang ra tiệm không, và chi phí sửa khoảng bao nhiêu, vì thứ năm này em phải nộp báo cáo rồi. À, còn một chuyện nữa, hôm qua em có cài thêm một phần mềm diệt virus miễn phí tải trên mạng, không biết có liên quan không. Nếu phải cài lại Windows thì em sợ mất hết dữ liệu, trong đó có mấy file rất quan trọng chưa sao lưu.

### vi04 · ~30 s · soft · heldout

*Nhỏ: nói nhỏ như trong phòng có người đang ngủ, không thì thầm.*

Bác sĩ ơi, mấy hôm nay tôi bị ho khan, sốt nhẹ khoảng ba mươi bảy độ rưỡi, buổi tối thì đau họng nhiều hơn. Tôi không bị dị ứng thuốc gì cả, nhưng tôi đang uống thuốc huyết áp mỗi sáng một viên. Tôi có nên uống thêm thuốc hạ sốt không, hay là phải đi xét nghiệm trước rồi mới uống thuốc? Tôi năm nay năm mươi tám tuổi, nặng khoảng sáu mươi lăm ký. Tuần trước tôi có đi đám cưới ở Cần Thơ, ngồi xe khách gần bốn tiếng, chắc là bị lây từ đó. Trong nhà tôi còn có cháu nội mới hai tuổi, nên tôi hơi lo.

### vi05 · ~60 s · normal · tuning

*Bình thường: giọng nói chuyện tự nhiên, tốc độ vừa phải.*

Xin chào mọi người, tôi xin cập nhật nhanh tiến độ dự án tuần này. Về ứng dụng di động, nhóm của chị Nguyễn Thị Minh Anh đã hoàn thành khoảng tám mươi phần trăm giao diện, còn lại màn hình thanh toán và màn hình cài đặt. Về phía máy chủ, anh Trần Quốc Bảo báo rằng thời gian phản hồi trung bình đã giảm từ một phẩy hai giây xuống còn ba trăm mili giây. Tuy nhiên, chúng ta vẫn chưa xử lý xong lỗi đăng nhập trên điện thoại Samsung đời cũ. Lỗi này xảy ra khoảng hai lần trên một nghìn lượt đăng nhập, không nhiều nhưng ảnh hưởng tới một khách hàng lớn. Tôi đề nghị dời ngày phát hành từ ngày hai mươi tháng mười một sang ngày hai mươi bảy tháng mười một, để có thêm một tuần kiểm thử. Nếu ai không đồng ý thì vui lòng phản hồi trước năm giờ chiều thứ sáu. Ngoài ra, về tài liệu hướng dẫn người dùng, em Lê Hoàng Nam đã viết xong bản tiếng Việt, còn bản tiếng Anh dự kiến xong vào thứ tư tuần sau. Chi phí thuê máy chủ tháng này là mười tám triệu đồng, tăng khoảng mười hai phần trăm so với tháng trước, chủ yếu do số người dùng thử nghiệm tăng gấp đôi. Tôi nghĩ mức tăng này vẫn chấp nhận được, nhưng nếu tháng sau vượt quá hai mươi lăm triệu thì chúng ta phải xem lại cấu hình.

### vi06 · ~60 s · normal · heldout

*Bình thường: giọng nói chuyện tự nhiên, tốc độ vừa phải.*

Hôm nay mình hướng dẫn mọi người nấu phở bò cho bốn người ăn. Đầu tiên, các bạn chuẩn bị khoảng một cân xương ống bò, nửa cân thịt thăn, hai củ hành tây, một nhánh gừng và một gói gia vị phở gồm quế, hồi, thảo quả. Xương rửa thật sạch, chần qua nước sôi khoảng năm phút rồi đổ bỏ nước đầu. Sau đó cho xương vào nồi với ba lít nước, ninh lửa nhỏ ít nhất bốn tiếng. Rất rất quan trọng là phải hớt bọt thường xuyên, nếu không nước dùng sẽ bị đục. Hành và gừng thì nướng cho thơm rồi mới thả vào nồi. Khi nêm, các bạn cho khoảng hai thìa canh nước mắm, một thìa đường phèn, nếm thử rồi chỉnh lại cho vừa. Thịt thái thật mỏng, chần nhanh, cho lên bánh phở, chan nước dùng thật nóng là xong. Một mẹo nhỏ là các bạn nên cho thêm một củ cải trắng vào nồi xương, nước dùng sẽ ngọt tự nhiên hơn mà không cần nhiều bột ngọt. Rau ăn kèm gồm húng quế, ngò gai, giá đỗ, chanh và ớt tươi. Nếu thích ăn tái thì đừng chần thịt, cứ để thịt sống lên bát rồi chan nước dùng đang sôi là thịt sẽ chín vừa tới. Phần nước dùng còn thừa, các bạn để nguội rồi cho vào tủ đông, dùng được trong khoảng một tuần.

### vi07 · ~60 s · noise · tuning

*Có tiếng ồn: bật quạt hoặc TV ở mức vừa, cách điện thoại khoảng 2 m.*

Alô, tôi gọi tới để phản ánh về đơn hàng số năm bảy hai chín tám. Tôi đặt hàng từ ngày ba tháng mười một, bên bạn cam kết giao trong hai ngày, nhưng tới hôm nay tôi vẫn chưa nhận được. Tôi đã gọi tổng đài hai lần, lần nào cũng được hứa là sẽ có người liên hệ lại, nhưng không ai gọi cả. Không, không, tôi không muốn đổi sang sản phẩm khác. Tôi chỉ muốn biết chính xác khi nào hàng tới. Nếu đến thứ bảy này vẫn chưa giao được thì tôi yêu cầu hoàn lại toàn bộ tiền, bao gồm cả phí vận chuyển ba mươi nghìn đồng. Ngoài ra, tôi mong bên các bạn kiểm tra lại quy trình, vì tôi thấy nhiều người cũng gặp tình trạng giống tôi. Thêm nữa, khi tôi kiểm tra trên ứng dụng, trạng thái đơn hàng ghi là đã giao thành công lúc ba giờ chiều hôm qua, trong khi cả ngày hôm qua tôi ở nhà và không hề nhận được cuộc gọi nào từ người giao hàng. Tôi nghi là có sự nhầm lẫn địa chỉ, vì tòa nhà của tôi có hai lối vào, lối A và lối B. Địa chỉ đúng là lô B, tầng mười bốn, căn hộ mười bốn không ba.

### vi08 · ~60 s · continuous · heldout

*Liền mạch: đọc một mạch, không dừng giữa các câu (lấy hơi thật nhanh).*

Để đăng ký tài khoản trên ứng dụng ngân hàng thì bạn mở ứng dụng lên, chọn mục mở tài khoản mới, sau đó nhập số điện thoại và mã xác thực gửi về tin nhắn. Tiếp theo bạn chụp hai mặt căn cước công dân và quay một đoạn video ngắn khuôn mặt để hệ thống đối chiếu. Khi đã xác minh xong, bạn đặt mật khẩu gồm ít nhất tám ký tự, có chữ hoa, chữ thường và số, rồi chọn gói tài khoản phù hợp. Gói cơ bản thì miễn phí, còn gói cao cấp thì mất năm mươi nghìn đồng mỗi tháng nhưng được rút tiền miễn phí ở tất cả các cây ATM trên toàn quốc, và được hoàn tiền một phần trăm khi thanh toán bằng thẻ. Lưu ý là bạn không được chia sẻ mã xác thực cho bất kỳ ai, kể cả nhân viên ngân hàng, vì ngân hàng không bao giờ gọi điện để hỏi mã này. Nếu nhập sai mật khẩu năm lần liên tiếp thì tài khoản sẽ bị khóa tạm thời trong hai mươi bốn giờ, và bạn phải mang căn cước tới chi nhánh gần nhất để mở khóa.

### vi09 · ~120 s · normal · tuning

*Bình thường: giọng nói chuyện tự nhiên, tốc độ vừa phải.*

Hôm nay tôi muốn chia sẻ một chút về tình hình xâm nhập mặn ở đồng bằng sông Cửu Long. Mùa khô năm nay, nước mặn đã vào sâu khoảng sáu mươi đến bảy mươi cây số tính từ cửa biển, sớm hơn trung bình nhiều năm gần một tháng. Ở Bến Tre và Trà Vinh, có những xã mà độ mặn đo được lên tới bốn phần nghìn, trong khi cây lúa chỉ chịu được khoảng hai phần nghìn. Vì vậy, nhiều hộ nông dân đã phải chuyển từ trồng lúa sang nuôi tôm, hoặc trồng các giống lúa chịu mặn như ST hai mươi lăm. Tuy nhiên, chuyển đổi không phải lúc nào cũng thành công. Nuôi tôm cần vốn ban đầu lớn, trung bình khoảng một trăm hai mươi triệu đồng cho mỗi héc ta, và nếu dịch bệnh xảy ra thì người nuôi có thể mất trắng. Theo tôi, giải pháp không chỉ nằm ở việc đổi cây trồng, mà còn ở việc quản lý nước. Chúng ta cần thêm các cống ngăn mặn, các hồ trữ nước ngọt nhỏ ở từng xã, và quan trọng nhất là hệ thống cảnh báo sớm để bà con biết trước khi nào nên lấy nước, khi nào nên đóng cống. Năm ngoái, một dự án thử nghiệm ở Sóc Trăng đã gửi tin nhắn cảnh báo độ mặn mỗi ngày cho hơn ba nghìn hộ dân, và kết quả là thiệt hại giảm gần một nửa so với năm trước. Tôi nghĩ mô hình này nên được nhân rộng, nhưng phải đi kèm với việc hướng dẫn bà con đọc và hiểu số liệu, nếu không thì tin nhắn gửi đi cũng không có tác dụng. Một vấn đề khác ít được nhắc tới là sạt lở bờ sông. Khi lượng phù sa từ thượng nguồn giảm, dòng chảy ăn sâu vào bờ, và chỉ trong năm năm qua, hàng trăm căn nhà ven sông Tiền và sông Hậu đã phải di dời. Nhiều gia đình nhận được tiền hỗ trợ, nhưng số tiền đó thường không đủ để mua đất mới ở nơi an toàn. Vì vậy, tôi cho rằng chính sách tái định cư cần được tính toán lại, không chỉ dựa trên diện tích đất bị mất, mà còn dựa trên sinh kế của người dân sau khi chuyển đi. Một người làm nghề đánh bắt cá cả đời, nếu bị chuyển vào một khu dân cư cách sông mười cây số, thì rất khó để bắt đầu lại. Cuối cùng, tôi muốn nhấn mạnh rằng không có giải pháp nào hiệu quả nếu thiếu sự tham gia của chính người dân địa phương, từ khâu lập kế hoạch cho tới khâu giám sát.

### vi10 · ~120 s · normal · tuning

*Bình thường: giọng nói chuyện tự nhiên, tốc độ vừa phải.*

Tháng trước, tôi và hai người bạn đi phượt Hà Giang bằng xe máy trong năm ngày. Chúng tôi xuất phát từ Hà Nội lúc năm giờ sáng, đi khoảng ba trăm cây số thì tới thành phố Hà Giang vào buổi chiều. Ngày thứ hai, chúng tôi đi qua dốc Bắc Sum và cổng trời Quản Bạ. Đường đèo rất đẹp nhưng cũng rất nguy hiểm, có những khúc cua gần như gấp đôi lại, nên chúng tôi đi chậm, chậm lắm, chỉ khoảng hai mươi cây số một giờ. Ngày thứ ba là đoạn đường tôi nhớ nhất: đèo Mã Pì Lèng. Đứng trên đèo nhìn xuống sông Nho Quế xanh như ngọc, cả ba đứa đều im lặng một lúc lâu. Buổi tối chúng tôi ngủ ở một homestay ở Đồng Văn, chủ nhà là người Mông, nấu cho chúng tôi món thắng cố và mèn mén. Thật lòng mà nói, thắng cố không hợp khẩu vị của tôi lắm, nhưng mèn mén thì rất ngon. Ngày thứ tư trời mưa to, nhiệt độ xuống còn mười hai độ, xe của bạn tôi bị thủng lốp giữa đường. Chúng tôi phải đẩy xe gần một cây số mới tới được tiệm sửa xe. Ông chủ tiệm vá lốp chỉ lấy ba mươi nghìn đồng, còn mời chúng tôi uống trà nóng. Ngày cuối, chúng tôi về lại Hà Nội, mệt nhưng rất vui. Nếu bạn định đi Hà Giang, lời khuyên của tôi là đừng đi vào mùa mưa, nhớ kiểm tra phanh xe thật kỹ, và đừng cố đi quá nhanh chỉ để kịp lịch trình. Có một kỷ niệm nhỏ mà tôi nhớ mãi. Ở chợ phiên Đồng Văn, tôi gặp một bà cụ bán rượu ngô. Bà không nói được tiếng Kinh, còn tôi thì không biết tiếng Mông. Hai bên chỉ trao đổi bằng tay và nụ cười, vậy mà cuối cùng tôi vẫn mua được một chai rượu nhỏ giá năm mươi nghìn đồng, và bà còn tặng thêm cho tôi một túi hạt dẻ. Lúc đó tôi ước gì có một ứng dụng dịch giọng nói chạy được ở vùng núi không có sóng điện thoại. Về chi phí, cả chuyến đi năm ngày hết khoảng ba triệu rưỡi mỗi người, bao gồm xăng, ăn uống, chỗ ngủ và tiền thuê một chiếc xe số. Nếu đi theo tour thì sẽ nhàn hơn, nhưng giá cao gần gấp đôi.

### vi11 · ~120 s · mixed · tuning

*Đổi nhịp: đoạn đầu nói chậm, đoạn giữa nhanh, đoạn cuối bình thường.*

Tôi xin giới thiệu ngắn gọn về ứng dụng OneVoice Link mà nhóm chúng tôi đang phát triển. Đây là ứng dụng dịch giọng nói hai chiều giữa tiếng Việt và tiếng Anh, chạy hoàn toàn trên điện thoại, không cần kết nối Internet. Ứng dụng dùng bộ xử lý thần kinh của chip Qualcomm Snapdragon tám thế hệ hai để chạy mô hình nhận dạng giọng nói và mô hình dịch máy. Người dùng có thể dùng loa và micro của điện thoại, hoặc dùng một cặp tai nghe Bluetooth chung cho hai người. Khi một người nói xong, ứng dụng tự nhận biết ngôn ngữ, dịch, rồi đọc bản dịch bằng giọng tổng hợp. Trong phiên bản mới, chúng tôi đang thử nghiệm cho phép một người nói liên tục tới hai phút mà không bị cắt ngang. Trước đây, nếu nói quá mười giây thì phần sau sẽ bị mất, vì mô hình chỉ nhận tối đa mười giây âm thanh mỗi lần. Bây giờ, âm thanh được chia thành nhiều đoạn nhỏ, mỗi đoạn được nhận dạng ngay trong lúc người dùng vẫn đang nói, và các câu đã đủ ý sẽ được dịch trước. Nhờ vậy, khi người nói dừng lại, bản dịch gần như đã sẵn sàng. Tất nhiên vẫn còn nhiều việc phải làm. Chúng tôi cần kiểm tra kỹ những chỗ cắt đoạn, để không bị mất chữ hay lặp chữ, và cần đo xem điện thoại có bị nóng khi dùng liên tục ba mươi phút hay không. Nếu bạn có góp ý, xin hãy gửi email cho chúng tôi trước ngày mười ba tháng mười một. Về mặt kỹ thuật, khó nhất không phải là chạy từng mô hình riêng lẻ, mà là sắp xếp để chúng chạy cùng nhau mà không tranh giành tài nguyên. Bộ xử lý thần kinh chỉ chạy một mô hình tại một thời điểm, còn phần tổng hợp giọng nói lại chạy trên CPU, nên chúng tôi phải đo rất kỹ xem lúc nào nên ưu tiên nhận dạng, lúc nào nên ưu tiên đọc bản dịch. Một điểm nữa là micro và loa không bao giờ được hoạt động cùng lúc, nếu không ứng dụng sẽ tự nghe lại chính giọng đọc của mình và dịch lặp vô tận. Chúng tôi gọi đây là chế độ bán song công, giống như bộ đàm vậy.

### vi12 · ~120 s · noise · heldout

*Có tiếng ồn: bật quạt hoặc TV ở mức vừa, cách điện thoại khoảng 2 m.*

Kính gửi toàn thể nhân viên, phòng nhân sự xin thông báo một số thay đổi về chính sách làm việc từ xa, áp dụng từ ngày một tháng mười hai năm nay. Thứ nhất, mỗi nhân viên được làm việc từ xa tối đa hai ngày mỗi tuần, nhưng không được làm từ xa vào ngày thứ hai, vì đây là ngày họp toàn công ty. Thứ hai, việc đăng ký làm từ xa phải được quản lý trực tiếp phê duyệt trước ít nhất hai mươi bốn giờ. Nếu không có phê duyệt, ngày đó sẽ được tính là nghỉ phép không lương. Thứ ba, trong giờ làm việc từ chín giờ sáng đến năm giờ chiều, nhân viên phải bật trạng thái trực tuyến trên Microsoft Teams và trả lời tin nhắn trong vòng ba mươi phút. Chúng tôi hiểu rằng không phải ai cũng hài lòng với quy định này. Tuy nhiên, khảo sát nội bộ tháng trước cho thấy sáu mươi hai phần trăm nhân viên muốn có lịch cố định hơn, và nhiều nhóm gặp khó khăn khi phối hợp vì không biết đồng nghiệp làm ở đâu. Những trường hợp đặc biệt, chẳng hạn như nhân viên đang mang thai, đang chăm con nhỏ dưới mười hai tháng tuổi, hoặc có vấn đề sức khỏe được bác sĩ xác nhận, sẽ không bị giới hạn hai ngày, nhưng vẫn phải đăng ký với phòng nhân sự. Mọi thắc mắc, xin vui lòng liên hệ chị Lê Thu Hà ở số máy lẻ hai không bốn, hoặc gửi email trước ngày hai mươi lăm tháng mười một. Bên cạnh đó, công ty sẽ hỗ trợ mỗi nhân viên làm từ xa năm trăm nghìn đồng mỗi tháng để chi trả tiền Internet và tiền điện, khoản này sẽ được cộng trực tiếp vào lương. Nhân viên cũng có thể mượn màn hình rời và ghế làm việc từ kho của công ty, nhưng phải ký biên bản và hoàn trả khi nghỉ việc. Lưu ý rằng các thiết bị này không được mang ra nước ngoài và không được dùng cho mục đích cá nhân. Chúng tôi sẽ đánh giá lại chính sách này sau ba tháng, dựa trên năng suất làm việc và phản hồi của các nhóm. Xin cảm ơn.

## Tiếng Anh

### en01 · ~30 s · normal · tuning

*Bình thường: giọng nói chuyện tự nhiên, tốc độ vừa phải.*

I will only approve this plan if all of the following conditions are met. First, the budget must not exceed two hundred and fifty thousand dollars. Second, the pilot has to be finished before December fifteenth. Third, the client must confirm everything in writing. If any condition is missing, we will not go ahead, and we will meet again next Monday to review the other options.

### en02 · ~30 s · normal · tuning

*Bình thường: giọng nói chuyện tự nhiên, tốc độ vừa phải.*

Excuse me, how do I get to gate twenty-four? I just landed from Hanoi and my connecting flight to Singapore leaves at eleven forty-five. Do I need to go through security again, or can I walk straight to the gate? Also, is there a place to change Vietnamese dong into Singapore dollars before I board?

### en03 · ~30 s · fast · tuning

*Nhanh: nói nhanh hơn bình thường như khi đang vội, nhưng vẫn rõ chữ.*

Hi, I'd like to order two large pizzas for delivery. One pepperoni and one vegetarian, but no olives on the vegetarian one, please. Can you add a bottle of Coke and an order of garlic bread? The address is fifty-two Nguyen Hue Street, apartment nine B. I'll pay by card, and we need it before seven thirty if possible.

### en04 · ~30 s · soft · heldout

*Nhỏ: nói nhỏ như trong phòng có người đang ngủ, không thì thầm.*

Doctor, I've had a dry cough for about four days and a mild fever, around thirty-seven point five degrees. My throat hurts more at night. I'm not allergic to any medicine, but I take one blood pressure pill every morning. Should I take something for the fever, or do I need a test first?

### en05 · ~60 s · normal · tuning

*Bình thường: giọng nói chuyện tự nhiên, tốc độ vừa phải.*

Good morning, everyone. Here is a quick update on the project this week. On the mobile app, Minh Anh's team has finished about eighty percent of the screens. The payment screen and the settings screen are still in progress. On the server side, Bao reports that the average response time dropped from one point two seconds to three hundred milliseconds. However, we still haven't fixed the login bug on older Samsung phones. It happens about twice in every thousand logins, which is not much, but it affects one of our largest customers. I suggest we move the release from November twentieth to November twenty-seventh, so we get one more week of testing. If you disagree, please let me know before five p.m. on Friday.

### en06 · ~60 s · normal · heldout

*Bình thường: giọng nói chuyện tự nhiên, tốc độ vừa phải.*

Today I'll show you how to make fresh spring rolls for four people. You need two hundred grams of shrimp, two hundred grams of pork belly, one pack of rice paper, rice vermicelli, lettuce, mint and some cucumber. First, boil the pork for about twenty minutes, then slice it very thin. Cook the shrimp for two minutes, peel them and cut each one in half. Dip one sheet of rice paper in warm water for just a second or two, not longer, or it will tear. Put the lettuce, noodles and herbs near the bottom, roll it halfway, add three pieces of shrimp, fold in the sides and finish rolling. For the dipping sauce, mix three spoons of hoisin sauce with one spoon of peanut butter and a little warm water. It's really, really easy once you've done the first two.

### en07 · ~60 s · noise · tuning

*Có tiếng ồn: bật quạt hoặc TV ở mức vừa, cách điện thoại khoảng 2 m.*

Hello, I'm calling about my room, number four one two. I checked in last night and the air conditioner is not working. It's been almost thirty degrees in the room. I called the front desk at ten p.m. and they said someone would come up, but nobody came. No, no, I don't want a fan. I booked this room for five nights and I paid for a deluxe room with air conditioning. If it can't be fixed by this afternoon, I'd like to move to another room on the same floor, or get a refund for tonight. And I'd also appreciate it if the manager could call me back, because this is the second time this has happened at your hotel.

### en08 · ~60 s · continuous · heldout

*Liền mạch: đọc một mạch, không dừng giữa các câu (lấy hơi thật nhanh).*

To set up the new router, first plug the power cable in and wait until the light turns solid green, which takes about two minutes. Then connect your phone to the network name printed on the sticker under the router and open the setup page in your browser. Log in with the default password, which is also on the sticker, and the app will ask you to choose a new network name and a new password with at least twelve characters. After that, choose your time zone, turn on automatic updates, and restart the router. When the light is green again, connect your laptop and your smart TV to the new network, and you're done.

### en09 · ~120 s · normal · tuning

*Bình thường: giọng nói chuyện tự nhiên, tốc độ vừa phải.*

I'd like to talk about traffic in Ho Chi Minh City and why the first metro line matters so much. The city has around nine million people and more than eight million motorbikes. During rush hour, a trip of ten kilometers from District Seven to the city center can easily take an hour. Metro Line One runs about twenty kilometers from Ben Thanh Market to Suoi Tien, with fourteen stations, three of them underground. The project took more than ten years and cost over forty trillion dong, which is roughly one point seven billion dollars. When it opened at the end of last year, many people were skeptical. They said nobody would leave their motorbike at home. But in the first months, the line carried tens of thousands of passengers every day, and the number is still growing. In my opinion, one line is not enough to change the city. People need feeder buses, safe sidewalks and parking near the stations, otherwise the metro only helps those who live right next to it. The second thing is price. A single ticket costs between seven thousand and twenty thousand dong, which is fine for office workers, but students and workers from other provinces still compare it with the cost of fuel for their motorbike. If the city adds more lines and connects them well, I believe that in ten years, the way people move around Saigon will be very, very different from today.

### en10 · ~120 s · normal · tuning

*Bình thường: giọng nói chuyện tự nhiên, tốc độ vừa phải.*

Last summer, my family spent four days in Hoi An, and I want to tell you about it. We took an early flight from Hanoi to Da Nang, then a taxi for about forty-five minutes to the old town. Our hotel was a small place by the Thu Bon River, run by an old couple who had lived there for over thirty years. On the first evening, we walked through the old town when all the lanterns were lit. It was beautiful, but honestly, it was also very crowded. The next morning we woke up at five and went back before the tourists arrived, and it felt like a completely different place. We rode bicycles to An Bang Beach, which is only about four kilometers away. The water was warm, around twenty-eight degrees. On the third day, my daughter wanted a dress made by a tailor. They measured her in the morning, and the dress was ready the same evening for only three hundred and fifty thousand dong. We also took a cooking class, where we learned to make cao lau noodles and banh xeo. My son burned his first pancake, but the second one was perfect. On the last day it rained heavily, so we stayed in a cafe and played cards for hours. If you go to Hoi An, my advice is to stay at least three nights, avoid the old town between seven and nine in the evening, and don't buy anything before you compare prices in at least two shops.

### en11 · ~120 s · mixed · tuning

*Đổi nhịp: đoạn đầu nói chậm, đoạn giữa nhanh, đoạn cuối bình thường.*

Thank you for inviting me to this interview. My name is Tran Minh Duc, and I'm a software engineer with about four years of experience in mobile and embedded development. I graduated from the University of Science in Ho Chi Minh City, majoring in computer science. In my first job, I worked on an Android app for a delivery company, and I was responsible for the offline mode, which had to keep working even when drivers lost their connection for an hour or more. That project taught me a lot about testing on real devices instead of only on emulators. For the last two years, I've been working on on-device speech translation. Our team runs speech recognition and machine translation directly on a Snapdragon phone, without any cloud service. My main tasks were the audio pipeline, the Bluetooth routing and the performance measurements. For example, I reduced the waiting time after the speaker stops from about three seconds to under two seconds, and I made sure the app never records and plays audio at the same time. I'm applying for this position because I want to work on products that many people use every day, and because your team is building tools for people who don't speak the same language. I'm not the kind of person who stops at a demo. I like to measure, find what is really slow, and fix it properly. I'm happy to answer any questions.

### en12 · ~120 s · noise · heldout

*Có tiếng ồn: bật quạt hoặc TV ở mức vừa, cách điện thoại khoảng 2 m.*

Good afternoon, and welcome to the factory. Before we start the tour, I need to explain a few safety rules, and I'm sorry, but these are not optional. First, you must wear your helmet, your safety glasses and your ear protection at all times on the production floor, even if the machines look like they are turned off. Second, do not touch any equipment, any buttons or any boxes unless an operator asks you to. Third, stay inside the yellow lines on the floor. Forklifts drive outside those lines, and the drivers cannot always see you. If you hear a long alarm, that means there is a fire or a chemical leak. Do not run, and do not go back for your bags. Walk calmly to the nearest green exit sign and wait at assembly point B, next to the parking lot, until someone counts everyone. If you hear three short beeps, that is only a test, and you can continue. Photos are allowed in the office area, but not on the production floor, because some of our customers do not allow it. The tour takes about forty-five minutes, and we will stop twice, once at the packing line and once at the quality lab, where you can ask questions. If anyone feels unwell, please tell me or my colleague Linh immediately. Does anyone have any questions before we go in?
