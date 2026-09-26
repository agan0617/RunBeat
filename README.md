# RunBeat

跑步用的 Android 節拍器。鎖螢幕、切到音樂 App 都會繼續打拍子，而且**不會讓音樂暫停**（刻意不搶音訊焦點，節拍跟音樂一起放）。

## 功能

- **BPM 60～240**：拉桿或 −5／−1／+1／+5 微調，預設 170（常見的跑步步頻）
- **背景執行**：前景服務＋通知，通知上就有 −5／停止／+5，跑步中不用解鎖也能調
- **節拍聲音**：木魚、電子嗶聲、滴答、牛鈴、大鼓、腳踏鈸（全部程式合成，不帶音檔）；沒在播時點一下可以試聽
- **App 自己的音量**：0～100%，只調節拍聲，不動手機的媒體音量，跟音樂之間的大小聲自己配
- 設定（BPM、聲音、音量）會記住，下次打開照舊

## 安裝

到 [Releases](../../releases) 下載最新的 `.apk`，在手機上打開安裝（第一次要允許瀏覽器或檔案管理員安裝不明應用程式）。

**小米／HyperOS**：省電機制很兇，背景跑久了可能被系統關掉。到「設定 → 應用程式 → RunBeat → 省電策略」選「**無限制**」。App 第一次按開始時也會問要不要讓它不受電池最佳化限制，按允許。

## 為什麼節拍不會飄

不用 `Handler`／`Timer` 計時，而是用 `AudioTrack` 串流一段自己合成的 PCM，每一拍落在算好的取樣點上（取樣位置用 `double` 累加，BPM 不會因為取整數而累積誤差）。聲音卡的時脈就是節拍器的時脈，背景、鎖螢幕、CPU 忙都一樣準。

## 程式結構

| 檔案 | 內容 |
|---|---|
| `MainActivity.java` | 畫面：BPM、聲音選擇、音量、開始／停止、試聽、第一次的權限詢問 |
| `MetronomeService.java` | 前景服務：音訊執行緒、通知與通知上的按鈕、WakeLock |
| `Sounds.java` | 六種節拍聲的合成 |

設定存在 SharedPreferences（`runbeat`），畫面改了設定就送 `ACTION_UPDATE` 請服務重讀。

## 建置

需要 Android Studio 內建的 JDK（`C:\Program Files\Android\Android Studio\jbr`）與 SDK 34。

```
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
gradlew assembleRelease
```

產出 `app/build/outputs/apk/release/app-release.apk`（用這台電腦的 debug 金鑰簽章，換電腦建置的版本要先移除舊的才能裝）。

改版時改 `app/build.gradle` 的 `versionCode`（+1）與 `versionName`；發 Release 的 tag 用 `v` + versionName（例如 `v1.1`）。

⚠️ 從 Claude 桌面版開的 shell 建置時，`%TEMP%` 會被 MSIX 沙盒導走，Gradle 會報「Unable to establish loopback connection」。把 `TEMP`／`TMP` 設到 `D:\Temp\jdk`，並加 `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=D:\Temp\jdk -Djava.io.tmpdir=D:\Temp\jdk`。
