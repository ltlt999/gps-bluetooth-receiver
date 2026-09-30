# GPS接收器

> 接收并解析由「GPS蓝牙传输」发送端应用通过蓝牙 SPP 发出的 NMEA 0183 定位数据，实时显示经纬度、海拔、速度、航向与卫星状态。

与「GPS蓝牙传输」（发送端，见作者仓库 `gps-bluetooth-transfer`）配合使用：发送端把手机/车机的 GPS 定位打包成标准 NMEA 语句，本应用（接收端）在另一台设备上通过蓝牙串口接收，供车载导航、仪表等没有定位能力的设备使用。

## ✨ 功能

- **两种连接方式**：主动连接发送端（客户端），或等待发送端连入（服务器），与发送端的双模式一一对应
- **持续接收不中断**：连接失败或蓝牙断开后自动重连（间隔 2→30 秒递增，连上即复位），服务一直运行直到用户手动停止；接收中切换设备立即生效；已连接但 45 秒无数据时触发「链路假死看门狗」强制重连
- **NMEA 解析**：支持 GGA / RMC / GSA / GSV / VTG 语句，兼容 `GP` / `GN` 等 talker，逐句校验和验证
- **实时显示**：经纬度、海拔、速度、航向、卫星（使用/可见数）、UTC 时间与数据新鲜度
- **卫星详情**：逐颗列出编号、卫星系统（🇺🇸GPS / 🇨🇳北斗 / 🇷🇺GLONASS / 🇪🇺Galileo / 🇯🇵QZSS / 🇮🇳NavIC 国旗标识）、信噪比、仰角、方位角
- **接收统计**：语句数、字节数、平均速率、校验错误数
- **横竖屏自适应**：竖屏单列浏览；横屏左（连接）右（定位+统计）两大卡，底部卫星详情，首屏完整显示定位信息
- **接收日志**：右上角按钮弹窗查看，实时滚动，可一键清空

> 卫星系统识别：优先按 GSV 语句的 talker（`$BD`/`$GL`/`$GA`/`$GI`…）判断；发送端 v1.1.0 起按星座分组发送，老版本发送端（统一 `$GP` 前缀）则按卫星编号段推断。
- **后台稳定接收**：前台服务（`connectedDevice` 类型）保持链路，通知栏显示最新坐标并可直接停止
- **开机自动接收**：设置里开启后，开机自动按上次的模式和设备开始接收（兼容车机 QUICKBOOT，蓝牙未就绪时自动等待）。采用双保险：开机广播 + 界面冷启动兜底（部分 ROM 不投递广播而是直接拉起界面）；进程被系统杀掉后服务会带原参数自动恢复

## 📦 安装

- 到 [Releases](https://github.com/ltlt999/gps-bluetooth-receiver/releases) 下载最新 APK（release 签名版）安装；发送端请到 [gps-bluetooth-transfer](https://github.com/ltlt999/gps-bluetooth-transfer) 获取
- 或用 Android Studio 打开本工程自行构建（release 构建需自备 `release.keystore` 与 `keystore.properties`，二者不入库）

系统要求：Android 7.0（API 24）及以上；需与发送端先在系统蓝牙设置中完成配对。

## 🚀 快速开始

1. 在两台设备上分别安装发送端与接收端，并在系统设置中将它们**蓝牙配对**
2. 打开发送端开始传输（默认「等待被连接」模式）
3. 打开接收端，点「选择」选中发送端 → 开始接收
4. 若发送端使用「主动连接」模式，则把接收端切到「等待发送端连接」后点开始

默认使用标准 SPP UUID（`00001101-0000-1000-8000-00805F9B34FB`），与发送端一致，无需额外配置。

## 📁 项目结构

- `app/src/main/java/com/gpsbt/receiver/bt/`：蓝牙 SPP 链路（客户端/服务器、读取线程）
- `app/src/main/java/com/gpsbt/receiver/nmea/`：流式 NMEA 解析器与校验和
- `app/src/main/java/com/gpsbt/receiver/service/`：前台接收服务
- `app/src/main/java/com/gpsbt/receiver/state/`：跨线程状态中心与日志总线
- `app/src/main/java/com/gpsbt/receiver/ui/`：列表适配器
- `app/src/test/`：NMEA 解析、校验和与格式化的单元测试

## 🛠️ 开发

```bash
./gradlew.bat testDebugUnitTest    # 单元测试
./gradlew.bat assembleRelease      # release 签名 APK
```

签名凭据读取根目录 `keystore.properties`（不入库），密钥为 `release.keystore`，与发送端共用同一把。

> ⚠️ Windows 已知问题：系统区域为中文（GBK 代码页）时，从命令行运行单元测试会因 Gradle 传给测试 JVM 的 UTF-8 classpath 被按 GBK 解码而报 `ClassNotFoundException`（发送端工程同样存在）。此时请改用 Android Studio 内运行测试，或将工程复制到纯英文路径下构建。

## 📝 License

未另行声明，默认保留所有权利。
