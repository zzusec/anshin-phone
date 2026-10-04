# 老人 Android 手机安全清理与远程协助：有界开源调研

核对日期：**2026-10-04（Asia/Shanghai）**。目标设备：华为 Android 9 / API 28，默认无 root。产品目标：安全清理、子女网页登录远程协助、广告与诱导防护。

本次仅调研指定项目的官方 GitHub 仓库、上游发布到 Maven Central 的制品、产品官方站点和 Android 官方资料。资料读取并行执行，不启动其他代理；没有安装、启动服务、连接或操作手机，没有读写原有清理日志。唯一交付文件是本文件。没有克隆整个仓库、构建应用、扫描其他候选项目或实测设备。

下文区分“上游已核实事实”和“本项目建议”。具体华为固件的权限差异、后台保活、包依赖及实际清理效果尚未真机验证，不以 AOSP 或上游分类代替实机结论。

## 1. 可以直接用于决策的结论

1. **Shizuku 接入依赖锁定 `api:13.1.5` 和 `provider:13.1.5`。** 官方 README 要求使用 Maven Central 最新版本；本次读取两个 artifact 的 metadata，`latest` 和 `release` 都为 `13.1.5`。这是客户端库版本，不是 Shizuku APK 的版本。GitHub 官方 `/releases/latest` 返回 APK 稳定版 `v13.6.0`，发布时间为 2025-05-25。[S1][S2]
2. **Android 9 无 root：需要电脑 ADB 启动，手机重启后要重新启动 Shizuku。** Android 11+ 的系统无线调试配对启动不适用于 API 28。不能承诺“老人手机重启后仍可由网页自动恢复 shell 权限”。因此，普通应用能力必须能独立工作，Shizuku 是可选增强，不是全部功能的运行前提。[S1]
3. **Java 提权调用采用 `UserService + AIDL`，不是普通 App 内的 `Runtime.exec`。** 已核对 `13.1.5` 的官方 sources JAR：`Shizuku.newProcess(...)` 是 `private`，不能作为公共 API 调用。UserService 的 Java 代码在独立的 shell UID 2000 进程执行；也可使用 `ShizukuBinderWrapper` 转发系统 Binder 调用，但后者需要对应 Android 版本的隐藏接口知识。[S1][S2]
4. **包分类优先参考 UAD-ng，但分类不等于华为 Android 9 的安全删除授权。** 可借鉴来源类别、风险等级、依赖与反向依赖、恢复流程；默认只输出建议，不批量执行。ADB AppControl 只作为交互参考：本次官方站点没有给出可核实的开源源码仓库或开源许可证，不能将它列成 GPL 开源库。[S3][S4]
5. **无 root 广告域名防护的轻量路线是 DNS-only 本地 `VpnService`，不是全流量防火墙。** DNS66/AdAway 的相关代码可用于理解 TUN、DNS 别名路由、规则匹配和上游 socket 保护；DNS66 当前官方仓库已归档，不建议直接作为持续维护的生产底座。Rethink 的能力明显更广，但引入原生网络栈会扩大工程和维护成本。[S5][S6][S7]
6. **DNS 防护不等于诱导防护。** 同域广告、硬编码 IP、应用内 DoH、系统 Private DNS 的 DoT、已缓存结果、离线内容和界面诱导不是一个明文 DNS 黑名单可以完整解决的问题。[S5][S7][S9][S10]
7. **AYA 是桌面 ADB 工具，不是现成的互联网远程协助平台。** 可借鉴应用管理与 scrcpy 的命令/媒体/控制分层；其 `RemoteControllerModal` 是按键控制界面，不是账号登录、家庭配对、跨互联网安全会话或命令授权服务。[S8]

## 2. 官方仓库、许可证与本次快照

许可证记录的是上游实际声明；发行时仍须逐项保留版权、许可证及第三方依赖声明。这里不把“借鉴设计”与“复制/链接代码”混为一谈，也不替代发行合规审查。

| 项目 | 官方仓库 / 官方站点 | 上游许可证 | 本次使用的依据与定位 |
| --- | --- | --- | --- |
| Shizuku 应用/服务端 | [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) | [Apache-2.0](https://github.com/RikkaApps/Shizuku/blob/master/LICENSE) | 官方 latest release 为 `v13.6.0`；它提供 ADB/root 后端，不等于客户端 API 库 |
| Shizuku API/provider | [RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API) | [MIT](https://github.com/RikkaApps/Shizuku-API/blob/a27f6e4151ba7b39965ca47edb2bf0aeed7102e5/LICENSE) | README/demo 快照 `a27f6e4151ba7b39965ca47edb2bf0aeed7102e5`；版本及签名另以 Maven Central `13.1.5` 发布制品校验 |
| Universal Android Debloater（原项目） | [0x192/universal-android-debloater](https://github.com/0x192/universal-android-debloater) | [GPLv3](https://github.com/0x192/universal-android-debloater/blob/11f27c671cba278d71296cdef4c5a5dba06add5e/LICENSE)，Cargo 声明 `GPL-3.0` | 快照 `11f27c671cba278d71296cdef4c5a5dba06add5e`；不把它与后续独立 fork 混用 |
| UAD-ng | [Universal-Debloater-Alliance/universal-android-debloater-next-generation](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation) | [GPLv3](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation/blob/5ccfd23a5203cb8958cc8c51c745962828d4ea18/LICENSE)，workspace Cargo 声明 `GPL-3.0` | 快照 `5ccfd23a5203cb8958cc8c51c745962828d4ea18`；作为当前分类资料入口，不直接自动同步执行 |
| ADB AppControl | [官方产品站](https://adbappcontrol.com/en/)、[官方文档](https://adbappcontrol.com/en/docs/) | **未核实到开源许可证**；官方站页脚为 All rights reserved | 不提供臆造的“官方开源 GitHub 地址”；不复制其代码、包库或付费功能 |
| DNS66 | [julian-klode/dns66](https://github.com/julian-klode/dns66) | [GPLv3 许可证文本](https://github.com/julian-klode/dns66/blob/3e80e35915dd9a501ba99779b01f53e906c2fffd/COPYING)；README 总体称 GPLv3-or-later，但明确部分文件 **GPLv3-only** | 快照 `3e80e35915dd9a501ba99779b01f53e906c2fffd`；GitHub `archived=true`，仅用作机制参考；逐文件声明优先 |
| AdAway | [AdAway/AdAway](https://github.com/AdAway/AdAway) | README 声明 **GPLv3+**；[许可证文本](https://github.com/AdAway/AdAway/blob/fa16432c3b8f3fa7fa42d76d094b2f285879726f/LICENSE.md)；VPN 衍生文件保留 DNS66 的具体声明 | 快照 `fa16432c3b8f3fa7fa42d76d094b2f285879726f`；README 当前要求 Android 8+，API 28 在声明范围内，尚未验证华为行为 |
| Rethink DNS + Firewall + VPN | [celzero/rethink-app](https://github.com/celzero/rethink-app) | 应用仓库 [Apache-2.0](https://github.com/celzero/rethink-app/blob/3d92d38268d6e1a76b4a6e9c4f6b4c6428b2721f/LICENSE) | 快照 `3d92d38268d6e1a76b4a6e9c4f6b4c6428b2721f`；不据此断言全部原生组件、规则订阅均同许可证 |
| AYA | [liriliri/aya](https://github.com/liriliri/aya) | [AGPLv3](https://github.com/liriliri/aya/blob/44c02f752a50a0f4be473ba7de452f0d2050fc83/LICENSE)，package.json 声明 `AGPL-3.0` | 快照 `44c02f752a50a0f4be473ba7de452f0d2050fc83`；Electron 桌面 ADB 工具 |

## 3. Shizuku：准确接入与 Android 9 边界

### 3.1 依赖版本：官方推荐的“当前最新”具体是什么

官方 README 没有写死数字，而是以 Maven Central badge 作为当前版本入口。2026-10-04 本次读取结果：

| artifact | `latest` | `release` | Maven metadata `lastUpdated`（UTC 格式） |
| --- | --- | --- | --- |
| `dev.rikka.shizuku:api` | `13.1.5` | `13.1.5` | `20230921012957` |
| `dev.rikka.shizuku:provider` | `13.1.5` | `13.1.5` | `20230921012958` |

`provider-13.1.5.pom` 的 `api` 依赖也是 `13.1.5`。应锁定两个版本，不使用 `+`、JitPack 不明 fork、旧的 `moe.shizuku.*` 客户端包名，也不要把 APK 的 `13.6.0` 填进客户端依赖坐标。[S1][S2]

```groovy
// repositories 中包含 mavenCentral()；以下是 app 模块示意。
def shizukuVersion = "13.1.5"

dependencies {
    implementation "dev.rikka.shizuku:api:$shizukuVersion"
    implementation "dev.rikka.shizuku:provider:$shizukuVersion"
}

android {
    // 使用下文 .aidl 文件时启用；部分新版 AGP 默认不启用 AIDL。
    buildFeatures {
        aidl true
    }
}
```

发布版 provider AAR 的 manifest 声明 `minSdkVersion=23`，因此 API 28 满足该库的最低系统版本。JDK 17 是构建机工具，不意味着 Android 9 能调用全部 Java 17 运行库；下文避免 `InputStream.readAllBytes()` 等不应直接假定可用的方法。[S2]

### 3.2 Manifest：provider 必须显式配置，权限/元数据由 AAR 合并

以下 provider 放在应用 `<application>` 内。`${applicationId}` 是 Gradle manifest placeholder，不是 Java package 常量。[S1]

```xml
<provider
    android:name="rikka.shizuku.ShizukuProvider"
    android:authorities="${applicationId}.shizuku"
    android:enabled="true"
    android:exported="true"
    android:multiprocess="false"
    android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
```

注意两类权限的区别：

- `android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"` 是 **provider 的访问保护**，不是给本应用申请跨用户系统权限；不要改成无保护的 exported provider，也不要当成 `<uses-permission>` 提权办法。
- `provider:13.1.5` 的发布 AAR 会合并以下声明。正常依赖该库时无需再手工重复；以最终 merged manifest 验收。它们也不能替代运行时 `Shizuku.requestPermission(...)` 授权。[S2]

```xml
<!-- 发布版 provider AAR 中：位于 manifest 根级 -->
<uses-permission android:name="moe.shizuku.manager.permission.API_V23" />

<!-- 发布版 provider AAR 中：位于 application 内 -->
<meta-data
    android:name="moe.shizuku.client.V3_SUPPORT"
    android:value="true" />
```

本项目建议先保持单进程。若将来多个应用进程都调用 Shizuku，按官方要求在每个需要调用的进程启用 `ShizukuProvider.enableMultiProcessSupport()`；不要将 `android:multiprocess="true"` 当成等价替代。[S1]

### 3.3 启动与权限：不能承诺无人值守自恢复

| 情况 | 已核实的 Shizuku 行为 | 对本项目的含义 |
| --- | --- | --- |
| Android 9，无 root | 通过电脑 ADB 启动；重启手机后需要重新启动后端 | 首次部署必须有现场电脑/授权步骤；重启后界面显示“增强权限不可用”，而不是伪装清理成功 |
| Android 11+，无 root | 系统无线调试可用于设备内启动 | 不将这套流程展示给 API 28 用户 |
| ADB 启动后端 | 后端 UID 为 `2000`（shell） | shell 不是 root；受 Android 权限、SELinux 和固件实现限制 |
| root/Sui 后端 | 后端 UID 可为 `0` | 本项目默认不采用；示例主动只接受 UID 2000 |
| 后端死亡/连接失效 | 需处理 Binder dead，不能继续假定授权可用 | 检查活性、重新建立连接；中断中的危险操作须核实状态后再决定是否重试 |

手机“重启”和应用 Activity 销毁不是同一事件：上游要求重启后重新启动后端，但不能反过来断言每次关闭 App 都需要电脑重启 Shizuku。[S1]

启动命令应使用安装中的官方 Shizuku 应用当前显示的电脑启动指引，不把随版本/安装位置可能变化的脚本路径硬编码进产品。华为 EMUI 的开发者选项、USB 调试授权、后台限制和具体命令是否被允许，仍需在用户许可下另行验证。本次没有打开 ADB、没有执行 `adb devices`，也没有开启 TCP 调试端口。

### 3.4 Java shell 权限调用示例：UserService + AIDL

下面是**本项目编写的只读诊断示例**，不是上游整段代码复制，也不是已经落地的应用。公共 API 签名已对照官方 `api-13.1.5-sources.jar`；AIDL 的保留销毁编号对照官方 demo。示例要求 Shizuku 后端 v13+，便于使用非 daemon UserService，并要求 shell UID 2000。[S1][S2]

设计上不提供 `execute(String command)` 给网页或普通 Binder 调用者。只允许固定的 `/system/bin/id`，证明命令确实在 shell 进程执行。后续增加包操作时应新增窄接口，在手机本地执行允许列表、用户授权、状态检查和结果验证。

**A. AIDL：** 预期路径 `app/src/main/aidl/example/cleanup/ICleanupShell.aidl`。这是文档示意路径，本次没有创建这些源码文件。

```aidl
package example.cleanup;

interface ICleanupShell {
    void destroy() = 16777114;
    String shellIdentity() = 1;
}
```

`16777114` 是 AIDL 显式编号；生成 Binder transaction code 为 `16777115`，这是 Shizuku 约定的销毁调用编号，不要随意改写。[S1]

**B. UserService Java：** 它是实现 AIDL Stub 的普通 Java 类，**不是** `android.app.Service`，不需要在 manifest 添加对应 `<service>`。

```java
package example.cleanup;

import android.os.RemoteException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

public final class CleanupShell extends ICleanupShell.Stub {
    // Shizuku 通过反射创建实例：保留 public 无参构造器。
    public CleanupShell() {}

    @Override
    public String shellIdentity() throws RemoteException {
        int uid = android.os.Process.myUid();
        if (uid != 2000) {
            throw new RemoteException("Expected shell UID 2000, got " + uid);
        }

        java.lang.Process child = null;
        try {
            child = new ProcessBuilder("/system/bin/id")
                    .redirectErrorStream(true)
                    .start();
            if (!child.waitFor(3, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                throw new RemoteException("id timed out");
            }
            // 仅适用于 id 这种固定、极小输出；不是通用命令执行器。
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(child.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            if (child.exitValue() != 0) {
                throw new RemoteException("id exit=" + child.exitValue()
                        + ": " + output);
            }
            return "Java process uid=" + uid + "\n" + output;
        } catch (IOException e) {
            throw new RemoteException("id I/O failed: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteException("id interrupted");
        } finally {
            if (child != null) child.destroy();
        }
    }

    @Override
    public void destroy() {
        System.exit(0);
    }
}
```

这个 `ProcessBuilder` 必须处于 UserService 内部：在普通 App 进程写同样的代码，仍然是 App 自己的 UID，不会因为安装了 Shizuku 或添加了 manifest 权限而变成 shell。[S1]

如果未来换成包列表等可能有大量输出的命令，需要边读取边等待、限制输出大小并实现整体超时；不能照抄上面的“小输出先 waitFor”模式，否则可能因管道缓冲区填满而卡住。破坏性操作更不能通过字符串拼接或 `sh -c` 接收远程输入。

**C. App 侧 Java：** 下例包含 Binder 活性、版本、授权回调、shell 身份确认、绑定和释放。`isPreV11()` 指 **Shizuku 后端版本低于 v11**，不是 Android 系统低于 Android 11。

```java
package example.cleanup;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import rikka.shizuku.Shizuku;

public final class ShizukuProbeActivity extends Activity {
    private static final int REQUEST = 28;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Shizuku.UserServiceArgs args;
    private boolean bindRequested;
    private ICleanupShell remote;

    private final Shizuku.OnBinderReceivedListener received =
            () -> report("Shizuku 已连接，请在本机点击授权/诊断");
    private final Shizuku.OnBinderDeadListener dead = () -> {
        remote = null;
        bindRequested = false;
        report("Shizuku 已断开，增强操作不可用");
    };
    private final Shizuku.OnRequestPermissionResultListener permission =
            (code, result) -> {
                if (code != REQUEST) return;
                if (result == PackageManager.PERMISSION_GRANTED) {
                    requestOrBind();
                } else {
                    report("用户未授权，不执行 shell 操作");
                }
            };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            remote = ICleanupShell.Stub.asInterface(binder);
            probe(remote);
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            remote = null;
            bindRequested = false;
            report("UserService 已断开");
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Button button = new Button(this);
        button.setText("本机授权 / 只读 shell 诊断");
        button.setOnClickListener(v -> requestOrBind());
        setContentView(button);
        args = new Shizuku.UserServiceArgs(
                new ComponentName(this, CleanupShell.class))
                .tag("cleanup-readonly-probe")
                .version(1)
                .daemon(false)
                .processNameSuffix("cleanup_shell");
        Shizuku.addBinderDeadListener(dead);
        Shizuku.addRequestPermissionResultListener(permission);
        Shizuku.addBinderReceivedListenerSticky(received);
    }

    private void requestOrBind() {
        try {
            if (!Shizuku.pingBinder()) {
                report("后端未启动；Android 9 无 root 需电脑 ADB 启动");
                return;
            }
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 13) {
                report("此示例要求 Shizuku 后端 v13+");
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    report("请先在 Shizuku 中检查本应用的授权状态");
                } else {
                    Shizuku.requestPermission(REQUEST);
                }
                return;
            }
            if (Shizuku.getUid() != 2000) {
                report("此无 root 示例仅接受 shell UID 2000");
                return;
            }
            if (remote != null) {
                probe(remote);
            } else if (!bindRequested) {
                Shizuku.bindUserService(args, connection);
                bindRequested = true;
            } else {
                report("正在等待 UserService 连接");
            }
        } catch (RuntimeException e) {
            report("Shizuku 调用失败：" + e);
        }
    }

    private void probe(ICleanupShell service) {
        worker.execute(() -> {
            try {
                report(service.shellIdentity());
            } catch (RemoteException | RuntimeException e) {
                report("shell 诊断失败：" + e);
            }
        });
    }

    private void report(String text) {
        runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(received);
        Shizuku.removeBinderDeadListener(dead);
        Shizuku.removeRequestPermissionResultListener(permission);
        if (bindRequested && Shizuku.pingBinder()) {
            try {
                Shizuku.unbindUserService(args, connection, true);
            } catch (RuntimeException e) {
                Log.e("CleanupProbe", "UserService cleanup failed", e);
            }
        }
        remote = null;
        worker.shutdownNow();
        super.onDestroy();
    }
}
```

集成注意事项：

- 普通的 `Activity` 需要按宿主应用方式注册；不要额外将 `CleanupShell` 注册成 Android Service。
- R8/ProGuard 不能移除或改掉反射入口。按实际包名保留，例如 `-keep class example.cleanup.CleanupShell { *; }`。稳定的 `.tag(...)` 用于 UserService 标识；更改服务实现时提高 `.version(...)`，不要将它当依赖版本。[S1]
- 示例是短生命周期、本机按钮触发的只读探针；生产应用需把连接状态纳入自身生命周期，防止过期回调更新已关闭界面，并给绑定超时提供可见错误。不能直接把这个 Activity 当长期远程守护进程。
- `ShizukuBinderWrapper` 是另一条正式路线，但必须正确获得目标系统 service Binder、使用相应 Android 9 隐藏接口并处理华为差异；普通 `context.getPackageManager()` 并不会自动获得 shell 身份。UserService 的 `Context` 也不是正常 App Context，官方明确提醒部分注册广播、ContentResolver 等 API 不可按普通应用方式使用。[S1]
- 示例未集成编译、未打包、未真机执行。已核对公共 API 签名、provider 发布 manifest 和官方 UserService/AIDL 约定；不能由此宣称所有华为包操作可用。

### 3.4.1 对接当前骨架：只接受严格包名数组的卸载/restore

主代理已明确不向网页开放任意 shell，仅允许经过严格校验的包名数组进行卸载/restore。可保留上面的连接、授权和销毁流程，把业务 AIDL 收窄为两个具名方法，例如：

```aidl
// 接口契约示意；具体实现由当前骨架提供，不与只读示例混用同一版本。
package example.cleanup;

interface IPackageActions {
    void destroy() = 16777114;
    String[] uninstallForOwner(in String[] packages) = 1;
    String[] restoreForOwner(in String[] packages) = 2;
}
```

手机端 UserService 再次校验数组大小、包名格式、本地已批准的包集合、保护列表和目标用户；严格包名格式本身不是删除授权。每个包分别构造固定 argv，例如 `new ProcessBuilder("/system/bin/pm", "uninstall", "--user", "0", packageName)`，或 `new ProcessBuilder("/system/bin/cmd", "package", "install-existing", "--user", "0", packageName)`；不接收网页传来的命令、选项或用户编号，不通过 `sh -c` 拼接。这里是固定命令形状，不是已经验证过华为固件效果的业务实现。

批量操作不是事务：逐包返回状态并核实实际安装/停用状态；部分失败不得报告整批成功，也不自动重试不可逆操作。`restore` 在这里仅指符合条件的“现存包重新安装到指定用户”，不代表恢复卸载时丢失的数据或重建不存在的第三方 APK。操作输出须有界并并发消费，超时后先核实设备状态，再决定是否重试。既有清理日志仍然保持不动。

### 3.5 shell 不是 root，更不是“无限清理权限”

官方明确 shell 不能随意读取 `/data/user/0/<其他包>` 的私有数据。Shizuku 不绕过 SELinux、不把普通包管理调用变成系统应用，也不创造设备管理员/Device Owner 身份。[S1]

本项目建议：清理先从可解释的存储/应用诊断、用户明确选择的共享文件、允许撤销的包停用做起。不承诺清理所有应用私有缓存，不将 `pm clear` 包装成“只清缓存”，不将 `pm uninstall --user` 包装成“删除系统 APK 释放系统分区”。

## 4. UAD / UAD-ng / ADB AppControl：包分类如何借鉴

### 4.1 当前 UAD-ng 数据结构

本次实际读取 UAD-ng 快照的 `resources/assets/uad_lists.json`，共 **5,381** 条条目。包名是 key，字段包括 `list`、`description`、`dependencies`、`neededBy`、`labels`、`removal`。[S3]

| 维度 | 快照中的实际取值 | 可以借鉴的做法 |
| --- | --- | --- |
| 来源分类 `list` | `Aosp`、`Carrier`、`Google`、`Misc`、`Oem` | 来源说明与功能说明分开；不能把 `Oem` 全部当垃圾 |
| 移除建议 `removal` | `Recommended`、`Advanced`、`Expert`、`Unsafe` | 保留风险分层及人工解释；未知包单独标为未核实 |
| 依赖 | `dependencies`、`neededBy` | 展示直接和反向影响；空数组不证明没有运行期隐式依赖 |
| 人类说明 | `description`、`labels` | 解释为什么建议保留/停用；保留来源与资料版本 |

原 UAD 的 README 明确提醒移除关键系统包可能导致 bootloop，并说明无 root 不能真正移除系统分区中的系统应用。UAD-ng 是原项目的独立 fork，不能将两个仓库的描述和版本混作一个发行物。[S3]

**本项目建议的安全执行策略：**

1. 清单只提供候选解释，不能直接变成远程批量删除名单。需结合这台手机的 Android/EMUI 版本、包版本、用户编号及当前启用状态建立本地审查结论。
2. `Recommended` 也只默认“可评估”；`Advanced/Expert/Unsafe` 和未知条目不进入老人端的一键操作。电话、短信、联系人、桌面、设置、包安装器、SystemUI、权限与网络相关关键组件建立本地保护列表。
3. “停用”“当前用户卸载”“清除数据”“共享文件删除”是不同操作，分别说明后果。优先停用、验证、再讨论更不可逆的动作；恢复须回到原来的状态，不能无条件 `pm enable` 覆盖既有设置。
4. `cmd package install-existing` 只对仍然存在于设备上的包等相应情形有意义；不是任意第三方 APK 或被删除用户数据的万能恢复。清除数据和删除文件不能靠“启用应用”撤销。
5. 如采用 GPL 分类资料或其翻译/修改，应保留上游来源、声明和修改记录，并按实际复制范围审查许可证要求；不是因为“JSON 不是代码”就可以移除许可。规则更新先审查再发布，不拉取上游最新 JSON 后立即执行。
6. 新功能采用独立的操作记录与明确的回滚资料；原有清理日志保持不动。本次没有将上游包分类与现有清理日志做匹配，也没有给出这台手机的可删包名单。

### 4.2 ADB AppControl 的定位

官方产品站提供应用停用/卸载、批量预设、APK 安装、权限工具、shell 等功能描述。可以借鉴“动作分开”“预设可预览”“先看应用详情再执行”的交互思想，但本次没有核实到官方源码 GitHub 仓库或 OSI 开源许可。[S4]

结论不是断言“世界上不存在任何相关源码”，而是：**在本次官方站点与官方文档范围内，没有依据把 ADB AppControl 当成可复制的开源实现或开源包知识库。** 不采用第三方同名仓库、反编译结果或非授权转载清单作为官方来源。

## 5. DNS66 / AdAway / Rethink：路线与取舍

| 项目 | 上游技术路线（已核实） | 本项目可借鉴 | 不能照搬/不能据此承诺 |
| --- | --- | --- | --- |
| DNS66 | 本地 VpnService；建立 DNS 别名地址和相应路由；解析 DNS、按域名规则阻断/转发；上游 DatagramSocket 使用 `protect()`。代码显式 `allowBypass()` 和 `allowFamily(AF_INET/AF_INET6)` | 轻量 DNS-only 架构、hosts 规则读取、例外列表、网络变化后的重建 | 当前仓库已归档；README 部分早期说明陈旧，实际 IPv6 代码已有相关处理。不能直接拿 README 的“IPv6 不支持”当当前源码结论；也不能因存在 IPv6 代码就宣布双栈完全防护。[S5] |
| AdAway | 两条路线：root 修改 hosts；无 root 使用本地 VPN。VPN 文件明确源自 DNS66，VpnWorker 通过受保护 socket 转发 DNS | hosts 源管理、允许/阻止列表、可见的 VPN 状态、暂停/恢复与网络重连 | shell/Shizuku UID 2000 不等于 root，不能把写系统 hosts 的方案搬到默认无 root；VPN 模式仍不是任意全流量按应用防火墙。[S6] |
| Rethink | VPN + Go `firestack` 网络处理；支持 DNS-only 与防火墙等模式、DoH/DoT/DNSCrypt/ODoH 上游，以及更广的 UDP/TCP 流处理、代理/VPN 组合 | DNS 规则与防火墙规则分层、故障/网络状态管理、加密上游选择、较清晰的例外和日志模型 | 不是几行 Builder 配置就能得到全套功能；涉及原生网络栈、ABI、生命周期与功耗等成本。README 明确 Android 9 及以下依赖 `/proc/net/*` 做连接归属；DNS 按应用归属还有启发式限制，不能承诺每条 DNS 准确对应某个应用。[S7] |

**本项目建议：** 首阶段可以选择“自行实现窄范围 DNS-only + 明确防护边界”，或引导用户使用维护中的成熟产品，但不同时堆入三个 VPN。若确实需要按应用阻断硬编码 IP、限制自带加密 DNS或合并代理流量，再单独评估完整 VPN 数据平面；这不是轻量 DNS-only 的等价升级开关。

许可证方面，Shizuku 客户端 MIT、Rethink 应用仓库 Apache-2.0 的复用限制相对少；DNS66/AdAway 的 GPL 与 AYA 的 AGPL 代码复用必须提前纳入产品发行计划。AGPL 尤其需要考虑修改后经网络提供交互的对应源码义务；不能笼统断言所有独立服务都会自动变为 AGPL。各条 hosts/blocklist 的许可证还需单独核实，项目代码许可证不授权所有规则内容。[S1][S5][S6][S7][S8]

## 6. DNS-only VpnService 的真实边界

### 6.1 不是“拦截全部端口 53”的系统魔法

`Builder.addDnsServer()` 配置 DNS 服务器，`addRoute()` 按 **目的 IP 前缀** 路由；路由不是域名规则，也不是按 UDP/TCP 端口建立的规则。应用必须读取 TUN 包、正确解析、执行规则、转发允许请求并写回响应。仅调用 `establish()` 不会自动生成过滤器。[S5][S9]

DNS66 的 `newDNSServer()` 为 IPv4 上游分配 VPN 别名，并对该别名 `addDnsServer(alias)` / `addRoute(alias, 32)`。它不是对所有业务流量设置 `0.0.0.0/0`。同时其源码明确调用 `allowBypass()`；这个设置不能当成“不可绕过防护”照搬。[S5]

下面仅说明路由形状，位于自有 `VpnService` 内；**不是可运行的完整 DNS 代理实现**：

```java
// 示例地址；上线须检查地址/路由冲突，实际分配与网络重建由实现负责。
ParcelFileDescriptor tun = new Builder()
        .setSession("家庭 DNS 域名防护")
        .addAddress("192.0.2.1", 32)
        .addDnsServer("192.0.2.2")
        .addRoute("192.0.2.2", 32)
        .allowFamily(android.system.OsConstants.AF_INET)
        .allowFamily(android.system.OsConstants.AF_INET6)
        .setBlocking(true)
        .establish();
if (tun == null) {
    throw new IllegalStateException("VPN consent unavailable or revoked");
}
// 必须另有后台 TUN 读写、DNS 代理及连接关闭逻辑；不能停在这里。
```

这里的 IPv6 `allowFamily()` 只是允许该族在没有对应 VPN 路由时走底层网络，**不是启用 IPv6 DNS 检测**。若不设置 IPv6 地址、路由、DNS 或 allowFamily，Builder 官方说明该族可能被默认阻断；如果允许它，又没有覆盖相应 DNS 路径，则不能宣传 IPv6 查询已全部过滤。[S9]

如果改成 `addRoute("0.0.0.0", 0)` / `addRoute("::", 0)`，就必须处理相应 TCP、UDP 等业务数据包，否则会把正常网络导入 TUN 后丢掉，导致断网。不能用“全路由但只读 DNS 包”的伪实现获取全流量防护。

### 6.2 Android 9 最小平台接入条件

```xml
<!-- manifest 根级 -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />

<!-- application 内 -->
<service
    android:name=".DnsProtectionService"
    android:exported="true"
    android:permission="android.permission.BIND_VPN_SERVICE">
    <intent-filter>
        <action android:name="android.net.VpnService" />
    </intent-filter>
</service>
```

- 启动前调用 `VpnService.prepare(context)`；如返回 Intent，由用户在系统界面明确确认。返回 null 表示本应用当前已经具备建立 VPN 的准备状态，不表示代理已工作。[S9]
- API 26+ 的 VPN 服务需及时进入前台；API 28 使用通知渠道与可见通知，提供暂停/关闭/异常状态，不能隐藏运行状态。[S9]
- 转发到上游的 socket 应先成功 `protect(socket)`，避免再次进入自己的 VPN 形成环路；检查返回结果并显式处理失败。[S5][S6][S9]
- 用户撤销、VPN 被替换、进程死亡和网络切换时要关闭 TUN/socket、更新真实状态，不继续显示“正在保护”。
- 不把 Android 14+ 的前台服务类型、通知权限或 API 33 的 `addRoute(IpPrefix)` 等接口无条件抄到 API 28；本示例使用 `addRoute(String, int)`。

### 6.3 边界清单：产品必须如实告知

| 场景 | DNS-only 能做什么 / 不能做什么 | 本项目建议 |
| --- | --- | --- |
| 系统解析器查询已覆盖的 DNS 地址 | 可按解析出的域名阻断或放行；允许请求仍需有实际代理响应 | 报告规则版本、代理运行状态、命中域名，不以“VPN 已连接”证明生效 |
| 应用硬编码 IP，或自选另一 DNS 地址 | 不经过已覆盖的 DNS 端点，不能普遍阻止；已存在缓存/连接也不一定重新触发查询 | 明示旁路；若需要强约束，评估全流量按应用防火墙 |
| 应用内 DoH/DoT | 加密内容不能按普通 DNS 报文解析；只路由虚拟 DNS 地址时可能完全不进入代理 | 不宣传“所有广告流量阻断”，也不以 HTTPS 中间人解密作为默认方案 |
| Android 9 Private DNS | Android 9 AOSP 已有基于 TLS 的 Private DNS 配置与校验；与本地明文 DNS 代理的配合受模式及路由影响，可能绕过或出现解析失败 | 检测/提示冲突并验证 strict/opportunistic 等情况；不静默改变系统 Private DNS 设置。[S10] |
| IPv6 / TCP DNS / 大报文 | 要分别考虑地址、路由、A/AAAA、TCP 回退、截断和报文大小；只有 UDP 处理不能证明完整支持 | 用双栈、UDP/TCP、网络切换和受限网络做专项验证，不扩大已实现范围 |
| 同域广告、离线广告、界面诱导 | 无法从 HTTPS 路径/页面元素中区分；阻断业务主域可能同时损伤正常功能 | 配合应用风险提示、通知/悬浮窗权限教育及用户确认；不宣称 DNS 能理解“诈骗内容” |
| 已有其他 VPN | Android 每个用户/工作配置文件只有一个活动 VPN；新的连接会使已有接口失效 | 与用户现有 VPN 冲突时说明取舍，不偷偷抢占，也不承诺两个独立 VpnService 并存。[S9] |
| always-on / lockdown | DNS-only 并不自动等于全流量隧道；“阻止不经过 VPN 的连接”与业务流量走底层网络的设计可能冲突 | API 28 上先验证系统设置组合；老人场景不默认启用可能导致大面积断网的 lockdown |
| Huawei 后台限制 / 服务被杀 | 上游代码或系统前台服务要求不能证明特定 EMUI 固件会持续保活 | 独立呈现“已停止/需恢复”，不要把 Binder 或服务曾启动当成持续保护证据 |

本项目应将“VPN 已建立”“DNS 上游可用”“域名规则加载成功”“测试域名拦截生效”分成独立状态。规则更新失败可保留最后一份已验证规则，但须显示版本和失败；所有上游失败时，不静默改用未经授权的 DNS，也不能假装网络已经受到保护。这些是本项目建议，不是本次已实现能力。

## 7. AYA：应用管理与远程协助的可借鉴部分

### 7.1 本次实际核对的源码

- [`src/main/lib/adb/package.ts`](https://github.com/liriliri/aya/blob/44c02f752a50a0f4be473ba7de452f0d2050fc83/src/main/lib/adb/package.ts)：通过 ADB 查询当前用户与包列表，区分部分管理动作，并调用 adbkit 的安装/卸载/清数据能力。其桌面可信操作者假设不能直接套到接受互联网输入的手机端。
- [`src/main/lib/adb/scrcpy.ts`](https://github.com/liriliri/aya/blob/44c02f752a50a0f4be473ba7de452f0d2050fc83/src/main/lib/adb/scrcpy.ts)：推送 scrcpy 服务端 jar，并通过 ADB `CLASSPATH=... app_process ... com.genymobile.scrcpy.Server` 启动。已授权的 ADB 通道是前提，AYA 不是绕过 Android 权限的屏幕控制器。
- [`RemoteControllerModal.tsx`](https://github.com/liriliri/aya/blob/44c02f752a50a0f4be473ba7de452f0d2050fc83/src/renderer/main/components/overview/RemoteControllerModal.tsx)：方向键、电源、音量、Home/Back 等控制，通过 Electron 的 `main.inputKey` 调用设备能力；“remote”在这里不代表已经实现家庭账号远程登录。
- [`src/renderer/screencast/lib/ScrcpyClient.ts`](https://github.com/liriliri/aya/blob/44c02f752a50a0f4be473ba7de452f0d2050fc83/src/renderer/screencast/lib/ScrcpyClient.ts)：可作为后续媒体/控制分层的源码入口。本次没有继续深入该客户端协议或移植实现。[S8]

**可借鉴：** 当前用户感知、包详情与动作区分、查询与控制分层、屏幕流与输入控制分层、执行结果反馈。**不能照搬：** Electron 主进程的可信 IPC 假设、任意 shell 面板、面向专业操作者的批量停用/清数据界面，以及“有 ADB 就有权限”的前提。

AYA 源码包含 AGPL 约束；若产品希望保持不同的许可体系，优先独立实现通用设计，不直接复制组件后删掉许可证。涉及 scrcpy、adbkit 等依赖时另外核对各自许可；本次没有据 AYA 的根许可证推断这些依赖全部属于 AGPL。[S8]

### 7.2 子女网页登录：本项目建议补建的边界

指定项目提供的是本机、桌面或 VPN 能力，没有在本次核对范围内提供可直接复用的“家庭网页登录远程协助”完整服务。建议最小分层如下：

```text
子女浏览器登录
  → 家庭身份与绑定关系校验
  → 有期限、可撤销、与指定设备绑定的会话
  → 经过认证的手机出站连接（HTTPS/WSS）
  → 手机本地能力检查与逐项授权
  → 固定操作接口 / 用户确认 / 实际结果验证
```

服务端登录不是 Android 系统授权。手机端必须再次判断：谁在请求、设备配对是否有效、老人是否同意当前操作、命令是否过期/重放、对应权限是否仍可用。默认只开放状态查询、风险解释与经确认的窄范围动作；远程任意 shell、APK 安装、清数据和系统包批量卸载不作为默认能力。

远程画面与远程操作分开设计：普通 App 可评估 Android MediaProjection，但需要系统授权流程；ADB/shell 侧的 scrcpy 又以已启动的增强权限为前提。不要把网页登录、截图权限和输入注入权限视为同一种授权，也不要将 Android 14+ 的单次 token 规则直接描述为 Android 9 的既有行为。[S8][S11]

不向互联网暴露 ADB 5555、未经认证的本地 HTTP 服务或任意命令接口；不为获得持续控制而自动开启无障碍、绕过系统确认、使用 root 或强制修改系统安全设置。手机重启后若 Shizuku 未启动，远程状态查询可以继续作为普通应用功能，但 shell 操作必须明确拒绝；这不应阻断整个协助流程。

## 8. 范围内完成的验证与后续验收

**本次已完成：**

- 通过现有 `http://127.0.0.1:7890` 代理，以 `curl -x` 并行读取官方资料；检查 GitHub repo metadata、default branch/tree、许可证声明和固定 commit 的关键源码。
- 读取 api/provider Maven metadata、provider POM；在内存中解开 `api-13.1.5-sources.jar` 和 `provider-13.1.5.aar`，核对 Java 公共签名、`newProcess` 的 private 可见性、最低 API 和合并 manifest。未将 JAR/AAR 写入工作区。
- 实际解析 UAD-ng 分类 JSON，检查类别、风险等级、依赖字段和条目数量；核对 DNS66 路由/旁路设置、AdAway DNS 转发方式、Rethink 路线与 Android 9 连接归属说明，以及 AYA 指定源码。
- 没有修改其他文件、没有操作手机、没有改动现有日志。构建机 `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`、SDK `/opt/homebrew/share/android-commandlinetools` 是用户给定环境，不是此次成功构建的证据。

**网络诊断：** GitHub API/raw 和 Maven Central 经代理正常返回。Shizuku 官网站 `/guide/` 返回 HTTP 403，未对同一路径反复超时重试，改用同一作者的官方 API README/demo 与发布制品。猜测的 AYA 文档页、ADB AppControl 产品子路径返回 404，未将不存在的页面当证据，改用已确认的仓库树或官网入口。Android 9 变更概述页未提供可定位的 Private DNS 正文，最终以官方 AOSP Android 9 标签下的 `DnsManager.java` 为依据。没有关闭代理、切换权限模式或绕过站点访问限制。

**将来实施时的最小验收（本次未执行）：**

1. 在 API 28 构建目标中编译 AIDL/Java、检查 merged manifest 与 R8；实际 UserService `id` 输出必须为 UID 2000。分别验证未启动、拒绝、授权、Binder 死亡与手机重启后的状态。普通 App 的同类命令不能被误判成 shell。
2. 单个经审查的非关键测试包，先记录状态再停用并恢复；验证原状态、系统关键功能与数据没有被错误改变。无论成功或失败，都不覆盖原有清理日志。
3. DNS 代理分别验证允许/阻止域名、真实上游转发、UDP/TCP DNS、IPv4/IPv6、Private DNS 模式、应用 DoH、网络切换、另一个 VPN 启动、撤销授权和前台服务被杀。已知旁路必须可见，不以减少测试项来宣称全覆盖。
4. 远程协助验证未配对/越权家庭成员、过期/重放请求、撤销会话和缺少本机权限均被拒绝；用户能够随时停止协助。远程登录不得变成任意命令执行入口。

完成上述实施验收前，可确认的是“依赖、上游声明和设计边界已核实”，不是“这台华为手机已经能安全执行全部清理/防护功能”。

## 9. 主要官方证据索引

- **[S1] Shizuku 接入与 UserService：** [官方 API README 固定快照](https://github.com/RikkaApps/Shizuku-API/blob/a27f6e4151ba7b39965ca47edb2bf0aeed7102e5/README.md)、[官方 DemoActivity](https://github.com/RikkaApps/Shizuku-API/blob/a27f6e4151ba7b39965ca47edb2bf0aeed7102e5/demo/src/main/java/rikka/shizuku/demo/DemoActivity.java)、[官方 UserService](https://github.com/RikkaApps/Shizuku-API/blob/a27f6e4151ba7b39965ca47edb2bf0aeed7102e5/demo/src/main/java/rikka/shizuku/demo/service/UserService.java)、[官方 AIDL](https://github.com/RikkaApps/Shizuku-API/blob/a27f6e4151ba7b39965ca47edb2bf0aeed7102e5/demo/src/main/aidl/rikka/shizuku/demo/IUserService.aidl)、[Shizuku APK v13.6.0 官方发布](https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0)。
- **[S2] 发布依赖的权威版本与实际内容：** [api metadata](https://repo.maven.apache.org/maven2/dev/rikka/shizuku/api/maven-metadata.xml)、[provider metadata](https://repo.maven.apache.org/maven2/dev/rikka/shizuku/provider/maven-metadata.xml)、[provider 13.1.5 POM](https://repo.maven.apache.org/maven2/dev/rikka/shizuku/provider/13.1.5/provider-13.1.5.pom)、[api 13.1.5 sources JAR](https://repo.maven.apache.org/maven2/dev/rikka/shizuku/api/13.1.5/api-13.1.5-sources.jar)、[provider 13.1.5 AAR](https://repo.maven.apache.org/maven2/dev/rikka/shizuku/provider/13.1.5/provider-13.1.5.aar)。数字和 manifest 依据发布制品，不只依据主分支源码。
- **[S3] UAD：** [原项目 README](https://github.com/0x192/universal-android-debloater/blob/11f27c671cba278d71296cdef4c5a5dba06add5e/README.md)、[UAD-ng README](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation/blob/5ccfd23a5203cb8958cc8c51c745962828d4ea18/README.md)、[实际分类 JSON](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation/blob/5ccfd23a5203cb8958cc8c51c745962828d4ea18/resources/assets/uad_lists.json)、[UAD-ng Cargo 许可声明](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation/blob/5ccfd23a5203cb8958cc8c51c745962828d4ea18/Cargo.toml)。
- **[S4] ADB AppControl：** [官方站点](https://adbappcontrol.com/en/)、[官方文档入口](https://adbappcontrol.com/en/docs/)。本次不存在可提供且已核实的官方开源仓库链接。
- **[S5] DNS66：** [README 与许可范围说明](https://github.com/julian-klode/dns66/blob/3e80e35915dd9a501ba99779b01f53e906c2fffd/README.md)、[AdVpnThread.java](https://github.com/julian-klode/dns66/blob/3e80e35915dd9a501ba99779b01f53e906c2fffd/app/src/main/java/org/jak_linux/dns66/vpn/AdVpnThread.java)（`newDNSServer`、`protect`、`allowBypass`、`allowFamily`）、[DnsPacketProxy.java](https://github.com/julian-klode/dns66/blob/3e80e35915dd9a501ba99779b01f53e906c2fffd/app/src/main/java/org/jak_linux/dns66/vpn/DnsPacketProxy.java)、[repo metadata / archived 状态](https://api.github.com/repos/julian-klode/dns66)。
- **[S6] AdAway：** [README](https://github.com/AdAway/AdAway/blob/fa16432c3b8f3fa7fa42d76d094b2f285879726f/README.md)、[VpnService.java](https://github.com/AdAway/AdAway/blob/fa16432c3b8f3fa7fa42d76d094b2f285879726f/app/src/main/java/org/adaway/vpn/VpnService.java)、[VpnWorker.java](https://github.com/AdAway/AdAway/blob/fa16432c3b8f3fa7fa42d76d094b2f285879726f/app/src/main/java/org/adaway/vpn/worker/VpnWorker.java)。
- **[S7] Rethink：** [README 的路线及 Android 9 归属限制](https://github.com/celzero/rethink-app/blob/3d92d38268d6e1a76b4a6e9c4f6b4c6428b2721f/README.md)、[GoVpnAdapter.kt 源码入口](https://github.com/celzero/rethink-app/blob/3d92d38268d6e1a76b4a6e9c4f6b4c6428b2721f/app/src/main/java/com/celzero/bravedns/net/go/GoVpnAdapter.kt)、[应用仓库许可证](https://github.com/celzero/rethink-app/blob/3d92d38268d6e1a76b4a6e9c4f6b4c6428b2721f/LICENSE)。
- **[S8] AYA：** [官方 README](https://github.com/liriliri/aya/blob/44c02f752a50a0f4be473ba7de452f0d2050fc83/README.md)、[package.json 的技术栈/许可声明](https://github.com/liriliri/aya/blob/44c02f752a50a0f4be473ba7de452f0d2050fc83/package.json)、[AGPL 许可证](https://github.com/liriliri/aya/blob/44c02f752a50a0f4be473ba7de452f0d2050fc83/LICENSE)，关键应用管理/控制源码见第 7 节。
- **[S9] Android VPN 平台约束：** [VpnService 官方 API](https://developer.android.com/reference/android/net/VpnService)、[VpnService.Builder 官方 API](https://developer.android.com/reference/android/net/VpnService.Builder)。具体方法采用前须检查 Added in API level，不把新系统接口无条件用于 API 28。
- **[S10] Android 9 Private DNS 实现：** [AOSP 官方镜像 `android-9.0.0_r61` 的 DnsManager.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r61/services/core/java/com/android/server/connectivity/DnsManager.java)，包含 Private DNS off/opportunistic/provider-hostname、`useTls`、TLS 主机名/服务器配置和验证路径；不是对华为固件结果的实测。
- **[S11] 截屏系统授权：** [Android 官方 MediaProjection 文档](https://developer.android.com/media/grow/media-projection)。当前页面同时介绍较新 Android 规则，引用时需按系统版本区分；本次仅据此确认截屏与网页身份认证是不同授权边界。
