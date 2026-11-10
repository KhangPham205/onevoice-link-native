# 66 câu định tuyến sai: bằng chứng và nguyên nhân

Probe: 136 clip có đủ log (66 câu sai, 50 EN đúng, 20 VI đúng làm đối chứng). Chính sách: margin 0.15, EN offset -0.08, prior 0.15, floor -5.0.

Tái hiện quyết định gốc từ điểm + prior gốc: 66/66 câu sai khớp đúng quyết định và lý do trong bench.csv.

## Phân bố điểm (p10 / median / p90)

| nhóm | n | VI norm | EN norm | EN+offset − VI | token VI | token EN |
|---|---:|---:|---:|---:|---:|---:|
| EN nói, sai | 64 | -0.76 / -0.41 / -0.16 | -1.20 / -0.54 / -0.22 | -0.76 / -0.25 / +0.05 | +2.00 / +2.00 / +8.00 | +4.00 / +17.00 / +31.00 |
| EN nói, đúng (đối chứng) | 50 | -inf / -0.69 / -0.54 | -0.41 / -0.18 / -0.09 | +0.19 / +0.45 / +inf | +0.00 / +3.00 / +22.00 | +14.00 / +26.50 / +45.00 |
| VI nói, sai | 2 | -0.54 / -0.47 / -0.40 | -0.47 / -0.38 / -0.30 | -0.15 / +0.01 / +0.17 | +5.00 / +6.50 / +8.00 | +1.00 / +1.50 / +2.00 |
| VI nói, đúng (đối chứng) | 20 | -0.18 / -0.09 / -0.03 | -1.36 / -1.05 / -0.78 | -1.35 / -1.04 / -0.72 | +7.00 / +7.00 / +9.00 | +2.00 / +6.50 / +9.00 |

## Nhóm nguyên nhân

| nguyên nhân | số câu |
|---|---:|
| A. điểm sai chiều, vượt ngưỡng margin | 43 |
| B. điểm hơi sai chiều; prior không cứu được | 10 |
| C. điểm đúng chiều nhưng sát; prior kéo sai | 13 |

## Nếu chỉnh EN offset (cùng margin/prior, prior gốc)

| EN offset | câu sai được sửa | đối chứng bị làm hỏng |
|---:|---:|---:|
| -0.08 | 0/66 | 0/70 |
| +0.00 | 10/66 | 0/70 |
| +0.05 | 15/66 | 0/70 |
| +0.10 | 23/66 | 0/70 |
| +0.15 | 27/66 | 0/70 |
| +0.20 | 35/66 | 0/70 |
| +0.30 | 42/66 | 0/70 |

## Nếu thêm luật chặn giả thuyết quá ngắn

Luật: nhánh nào chỉ ra ≤ N token trong khi nhánh kia ra ≥ k lần số đó thì thua.

| N | k | câu sai được sửa | đối chứng bị làm hỏng |
|---:|---:|---:|---:|
| 2 | 4 | 29/66 | 0/70 |
| 3 | 4 | 34/66 | 0/70 |
| 3 | 3 | 39/66 | 0/70 |
| 4 | 3 | 43/66 | 0/70 |
| 5 | 3 | 46/66 | 0/70 |

## Từng câu sai

| clip | nói | → | lý do | prior | VI norm (tok) | EN norm (tok) | Δ | GIPFormer (VI) nghe | Parakeet (EN) nghe | bản dịch đã phát |
|---|---|---|---|---|---:|---:|---:|---|---|---|
| en_EV-0250__cutting_steam | en | vi | margin | vi | -0.09 (2) | -0.31 (34) | -0.29 | ờ | Helloy Dt. we will send your payment to fossil gas. Is there anything else I can help you with today | Yeah. |
| en_EV-0250__loom | en | vi | margin | vi | -0.39 (9) | -0.59 (31) | -0.28 | ờ chị xem là chị có kiểm tra | I if you don't. T we will se your favorite toacco gas if there anything else that can help you like | Yeah, well, you're gonna have to check. |
| en_EV-0250__workshop | en | vi | margin | en | -0.22 (2) | -0.35 (32) | -0.21 | ờ | Hello. So we will send your payment to bossil gas. Is there anything else I can help you with today | Yeah. |
| en_EV-0333__cutting_steam | en | vi | margin | en | -0.07 (3) | -0.22 (19) | -0.23 | à mai | and my ears are buzzing a bit. I' feeling a bit off | Tomorrow. |
| en_EV-0333__loom | en | vi | margin | en | -0.19 (3) | -0.43 (17) | -0.32 | à mai | and might use the fuing a bit. I'm feeling bit off | Tomorrow. |
| en_EV-0333__sewing | en | vi | margin | en | -0.17 (3) | -0.49 (17) | -0.40 | à mai | And might ear be buzzing a bit. I've se it off | Tomorrow. |
| en_EV-0333__workshop | en | vi | margin | en | -0.07 (3) | -0.22 (18) | -0.23 | à mai | And my ears are buzzing a bit.' feeling a bit off | Tomorrow. |
| en_EV-0523__loom | en | vi | margin | en | -0.56 (6) | -0.96 (10) | -0.48 | hai mốt đâu gì | Let' here. how what old is he now | You're not a two-way guy. |
| en_EV-0523__sewing | en | vi | margin | vi | -0.18 (1) | -0.68 (11) | -0.58 | gì | What's o And what old is he when now | What? |
| en_EV-0523__workshop | en | vi | margin | en | -0.64 (8) | -1.20 (9) | -0.64 | hai mốt đâu thì mình làm | I and how what old did you with that | I'll do it when I'm done |
| en_EV-1508__loom | en | vi | margin | vi | -0.30 (2) | -0.44 (27) | -0.22 | a lô | I'll look at it like when my people working hours or like from callcock or threecock or something | Hello? |
| en_EV-1829__loom | en | vi | margin | en | -0.45 (4) | -0.76 (13) | -0.38 | vô để coi như | know that I needed helpperate very much fer now | In and out of it, we'll be like |
| en_EV-1829__sewing | en | vi | margin | en | -0.34 (5) | -0.56 (14) | -0.30 | coi điện thoại của em | You time did help with, thank you very much for your help | Check my phone. |
| en_EV-1863__loom | en | vi | margin | vi | -0.89 (4) | -1.34 (14) | -0.53 | tôi biết hồi đó | Do this em to to about the same or you their quolder | I knew it back then. |
| en_EV-2364__cutting_steam | en | vi | margin | vi | -0.82 (2) | -1.11 (23) | -0.36 | eo | This is the stctions that people have because personal proty were the area what prove our or | Belt |
| en_EV-2401__loom | en | vi | margin | vi | -0.42 (10) | -0.58 (31) | -0.24 | ờ a lát nó xuống chỗ mình luôn | Hi, my name is Picher Johnson. and I lost my credit card. C for every one | Yeah, yeah, yeah, yeah. I'm gonna take it down to my place. |
| en_EV-2401__sewing | en | vi | margin | vi | -0.17 (6) | -0.34 (36) | -0.26 | ờ cho mình đúng không | Hi, my name is Patricia Johnson. and I love my deredit card. Can you send me a mo | Yeah, yeah, yeah, yeah, yeah, yeah, yeah, yeah, yeah, yeah, yeah. |
| en_EV-2748__cutting_steam | en | vi | margin | en | -0.87 (2) | -inf (0) | -inf | ừ |  | Yeah. |
| en_EV-2748__loom | en | vi | margin | en | -0.62 (2) | -inf (0) | -inf | ừ |  | Yeah. |
| en_EV-2748__sewing | en | vi | margin | vi | -0.90 (2) | -inf (0) | -inf | ừ |  | Yeah. |
| en_EV-2748__workshop | en | vi | margin | en | -0.93 (2) | -inf (0) | -inf | ừ |  | Yeah. |
| en_EV-4294__workshop | en | vi | margin | vi | -0.67 (2) | -1.13 (15) | -0.54 | ừ | We compular have for growing the global number of proers | Yeah. |
| en_EV-5269__loom | en | vi | margin | vi | -0.46 (2) | -0.70 (9) | -0.32 | noi | Not c dedication when wor | Copy |
| en_EV-5600__loom | en | vi | margin | en | -0.38 (2) | -0.70 (27) | -0.40 | dạ a | Yeah, I want to some for time for' time with my friend and outside like this really nice. But you will | Yes |
| en_EV-5935__loom | en | vi | margin | en | -0.14 (4) | -0.22 (29) | -0.16 | à mày | My nameor is Carisith and my date of birth is the twentieth of fourth nineteen eight | Hey, man. |
| en_EV-5935__sewing | en | vi | margin | en | -0.06 (2) | -0.22 (29) | -0.24 | à | my name is Cara Smith and my date of birth is the twentieth of a nineteen eight | Well... |
| en_EV-6218__loom | en | vi | margin | en | -0.25 (1) | -0.50 (27) | -0.34 | y | The byhaless, you dont you don't use it every day. you just use it on and off ye | y |
| en_EV-6239__cutting_steam | en | vi | margin | vi | -0.35 (3) | -0.53 (11) | -0.26 | ok rồi | Okay, G perfect. Thank you so much | Okay. |
| en_EV-6239__loom | en | vi | margin | en | -0.28 (2) | -0.65 (12) | -0.46 | ok | Okay, thir half, thank you very much | Okay. |
| en_EV-6239__workshop | en | vi | margin | vi | -0.49 (2) | -0.70 (12) | -0.29 | ok | Okay, first partay, thank you so much again | Okay. |
| en_EV-6602__sewing | en | vi | margin | en | -0.30 (2) | -0.54 (37) | -0.32 | op | probably do have this for a simple UPI or urine that inpectty, which can very be treated. I'll give you a quine. | op |
| en_EV-6960__cutting_steam | en | vi | margin | en | -0.32 (2) | -1.00 (6) | -0.76 | ai cần | I free ren today | Who needs it? |
| en_EV-6960__loom | en | vi | margin | vi | -0.98 (1) | -inf (0) | -inf | ai |  | Who? |
| en_EV-6960__sewing | en | vi | margin | vi | -0.71 (1) | -0.86 (14) | -0.23 | ai | 'c going to re a free and welln to work today | Who? |
| en_EV-6960__workshop | en | vi | margin | en | -0.52 (2) | -0.83 (11) | -0.39 | ai cần | I am going to be to reather to work today | Who needs it? |
| en_EV-7690__loom | en | vi | margin | en | -0.41 (2) | -1.16 (3) | -0.83 | ô kê | Oh takeaking | field |
| en_EV-7690__workshop | en | vi | margin | en | -0.52 (3) | -0.80 (10) | -0.36 | ok ta | Allright, o, take you the real best | Okay, I'm... |
| en_EV-8596__cutting_steam | en | vi | margin | vi | -0.27 (2) | -0.46 (7) | -0.26 | on | White would on interational work | on |
| en_EV-8596__workshop | en | vi | margin | vi | -0.57 (5) | -0.74 (7) | -0.25 | mo on chi li | White wouldood on uight | mo on chi |
| en_EV-9341__sewing | en | vi | margin | vi | -0.16 (3) | -0.75 (14) | -0.68 | ham ba | Im sure sure whichich one? how much are being B bl | love three |
| en_EV-9341__workshop | en | vi | margin | vi | -0.46 (1) | -0.54 (19) | -0.16 | a | Money short, short. Which one, how much are bring mable | A |
| en_EV-9953__cutting_steam | en | vi | margin | vi | -0.20 (2) | -0.30 (17) | -0.18 | ờ | Alllright, did you have any questions that you wanted to ask me | Yeah. |
| vi_VE-8860__sewing | vi | en | margin | en | -0.54 (8) | -0.30 (2) | +0.17 | anh có mang theo hủ trước không | I am | Tôi là |
| en_EV-0228__cutting_steam | en | vi | prior | vi | -0.02 (2) | -0.04 (24) | -0.09 | từ đó | Did I remember to set a reminder al for my next doctor's appointment | _(lần chạy lại đi hướng khác do prior)_ |
| en_EV-1212__workshop | en | vi | prior | vi | -0.29 (2) | -0.30 (16) | -0.09 | com | And is it pay there all the time? or does it come and go | com |
| en_EV-4177__loom | en | vi | prior | vi | -0.34 (2) | -0.40 (24) | -0.14 | hai mấy | Hi, my name is Michael Gilson. I would like to transform m to my | _(lần chạy lại đi hướng khác do prior)_ |
| en_EV-4177__workshop | en | vi | prior | vi | -0.24 (5) | -0.20 (26) | -0.04 | à máy này ạ | Hi, my name is Michael Wilson. I would like to transf the money between I | Oh, yeah. |
| en_EV-4250__loom | en | vi | prior | vi | -0.53 (6) | -0.45 (28) | -0.00 | ok cony ti | Okay, Right. And with the diarrhea, how many times a day I just go you the day day | _(lần chạy lại đi hướng khác do prior)_ |
| en_EV-5301__cutting_steam | en | vi | prior | vi | -0.56 (2) | -0.59 (14) | -0.11 | phô | Mis to confirm you set the alarm beforear | _(lần chạy lại đi hướng khác do prior)_ |
| en_EV-5600__sewing | en | vi | prior | vi | -0.51 (2) | -0.53 (28) | -0.10 | dạ vâng | Yeah, I want some for time quet time with my friends andide like. It's really nice. What you you | _(lần chạy lại đi hướng khác do prior)_ |
| en_EV-6054__loom | en | vi | prior | vi | -0.33 (2) | -0.25 (33) | -0.00 | nhiều lắm | Some people can suffer with cots and they can neither be found in the legs or your cet or your lung | _(lần chạy lại đi hướng khác do prior)_ |
| en_EV-6618__cutting_steam | en | vi | prior | vi | -0.61 (2) | -0.58 (6) | -0.06 | à | Al Thank you for calling | _(lần chạy lại đi hướng khác do prior)_ |
| en_EV-6618__workshop | en | vi | prior | vi | -0.59 (3) | -0.53 (4) | -0.02 | khóc chứ | Thank you for ha | We're crying. |
| en_EV-0182__loom | en | vi | prior | vi | -0.49 (2) | -0.36 (20) | +0.05 | ye | Well at one point yes but the tourism of f not so wonderful | ye |
| en_EV-1212__sewing | en | vi | prior | vi | -0.71 (1) | -0.54 (20) | +0.09 | pa | Okay And there's a pain is it there all the time? or does it com go | _(lần chạy lại đi hướng khác do prior)_ |
| en_EV-1829__workshop | en | vi | prior | vi | -0.42 (8) | -0.30 (14) | +0.04 | nhiều đợt sau của em kiểm tra sau | know thats I need to help with very much for your health | _(lần chạy lại đi hướng khác do prior)_ |
| en_EV-3791__loom | en | vi | prior | vi | -0.48 (16) | -0.37 (45) | +0.03 | com the amin soting the on | So thanks for coming and I'll send you in stock meeting and please puture all information in the seting folder so it is very confred information | _(lần chạy lại đi hướng khác do prior)_ |
| en_EV-4177__cutting_steam | en | vi | prior | vi | -0.32 (4) | -0.16 (26) | +0.08 | à máy này | Hi, my name is Michael Wilson. I would like to transform m in my account | Oh, yeah. |
| en_EV-4321__cutting_steam | en | vi | prior | vi | -0.31 (5) | -0.19 (26) | +0.04 | ok ami | Okay, I'm going to read out the facts now so we understand what you are supposed to do | _(lần chạy lại đi hướng khác do prior)_ |
| en_EV-6502__sewing | en | vi | prior | vi | -0.38 (2) | -0.29 (16) | +0.01 | à | I would give it long than that. Give it an hour or say | _(lần chạy lại đi hướng khác do prior)_ |
| en_EV-7227__sewing | en | vi | prior | vi | -0.52 (2) | -0.34 (19) | +0.10 | en | Any pain or strong smell or ha go more often than normal | _(lần chạy lại đi hướng khác do prior)_ |
| en_EV-8694__workshop | en | vi | prior | vi | -0.52 (11) | -0.32 (20) | +0.12 | rin sosi michils | Remve the list a person started from letter M in my contact list | _(lần chạy lại đi hướng khác do prior)_ |
| en_EV-9018__workshop | en | vi | prior | vi | -0.36 (5) | -0.20 (31) | +0.08 | go king | Google, I need the living room vacube, but can you turn on the vacuit cleaner? | go go go |
| en_EV-9341__cutting_steam | en | vi | prior | vi | -0.59 (8) | -0.50 (18) | +0.02 | mich wan hang bi | Money sure, sure. Which one? how much bing bedroom | _(lần chạy lại đi hướng khác do prior)_ |
| en_EV-9341__loom | en | vi | prior | vi | -0.76 (5) | -0.54 (14) | +0.14 | michai ba | Money sure sure. whichich one? how much I think my | Michai three |
| vi_VE-5468__sewing | vi | en | prior | en | -0.40 (5) | -0.47 (1) | -0.15 | đừng bắt tôi | you | bạn |
