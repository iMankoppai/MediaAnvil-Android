# 真机 instrumented 测试卡死的原因与解法

## 现象

在 vivo V2520A（Android 16 / OriginOS）上跑 `connectedDebugAndroidTest` 或直接跑 Compose UI
测试时，整个进程会**永久卡住**：

- 所有线程处于 `S`（sleeping），进程 CPU 占用 **0%**
- 测试宿主 Activity **从未启动**
- logcat **完全空白**
- 没有任何 ANR

看起来像测试代码有问题，实际上是**进程被冻结了**。

## 根因：vivo 的 `fast_freezer`

事件缓冲区里有一行决定性证据：

```
am_proc_start:  [0,19512,10489,com.imankoppai.mediaanvil.debug,added application]   # 19:09:34
am_app_frozen:  [0,10489,com.imankoppai.mediaanvil.debug,from fast_freezer]        # 19:09:40
```

进程启动约 **5–6 秒**后被 vivo 自己的 `fast_freezer` 冻结。而 AndroidJUnitRunner
在这段时间里正要把测试宿主 Activity 拉起来，进程一被冻结，后续调度全部停摆 ——
于是表现为「永远卡住、0% CPU、无日志」。

注意 **`settings put global cached_apps_freezer disabled` 无效**。该设置是 AOSP 的机制，
`fast_freezer` 是 vivo 自研的，会忽略它（已实测：关闭后仍出现 `from fast_freezer`）。
所以「关掉系统里的应用冻结」这条路走不通。

## 解法：先把进程预热到前台

前台进程不会被冻结。在启动 instrumentation 之前，先显式启动测试宿主 Activity：

```sh
adb shell am start -n com.imankoppai.mediaanvil.debug/androidx.activity.ComponentActivity
```

关键证据：直接 `am start` 该 Activity 能成功拿到焦点，且**这次进程没有被冻结**
（事件日志里只冻结了别的包）。预热之后再跑测试，从「永久卡死」变成 **1.177 秒通过**。

`tools/run-instrumented-tests.sh` 已把这个步骤固化：

```sh
tools/run-instrumented-tests.sh                                   # 全部 44 项
tools/run-instrumented-tests.sh com.imankoppai.mediaanvil.Phase5MainThreadIoTest
```

（需要 `bash`/`sh`；Git for Windows 自带，用 `D:\Git\bin\bash.exe` 即可。）

脚本还会先唤醒并上滑解锁：锁屏状态下安装和启动 Activity 都会被系统拒绝。

## 验证结果

| 场景 | 结果 |
| --- | --- |
| 预热后跑全部 | `OK (44 tests)`，连续 2 次复现 |
| 预热后按类过滤 | `OK (3 tests)` |
| 预热前 | 永久卡死（多次复现） |

## 这不是 App 的缺陷

- 同样的代码在 Google 模拟器（CI）上**一直是通过的**，`instrumented-tests` job 为 success。
- 触发条件是厂商的后台冻结策略，与 App 逻辑无关。
- 排查过程中确认过：合并清单里 `androidx.activity.ComponentActivity` 声明正常
  （`exported="true"`），`ui-test-manifest` 也已在 `debugImplementation` 中，
  所以**不是**常见的「测试宿主没打进清单」那类问题。

## 如果将来在别的厂商设备上复现

按同样的顺序看三处即可定位：

1. `adb shell ps -A | grep <pkg>` —— 进程在不在
2. `adb shell cat /proc/<pid>/stat` 的 CPU 时间是否增长 —— 不增长说明没被调度
3. `adb shell logcat -d -b events | grep -i freez` —— 有没有 `am_app_frozen`
