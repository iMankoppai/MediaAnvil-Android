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
am_proc_start:  [0,12310,10489,com.imankoppai.mediaanvil.debug,added application]   # 20:34:07
am_app_frozen:  [0,10489,com.imankoppai.mediaanvil.debug,from fast_freezer]        # 20:34:12
```

进程启动约 **5 秒**后被 vivo 自己的 `fast_freezer` 冻结。而 AndroidJUnitRunner 在这段时间里
正要把测试宿主 Activity 拉起来 —— 进程一冻，后续调度全部停摆。实测确认：不干预时**等待 16 秒
Activity 也从未出现**，主屏始终在最前，且该进程 CPU 时间完全不增长。

## 试过但无效的办法

| 办法 | 结果 |
| --- | --- |
| `settings put global cached_apps_freezer disabled` | **无效**。那是 AOSP 的机制，`fast_freezer` 是 vivo 自研的，忽略它 |
| `am set-standby-bucket <pkg> active` | 无效 |
| `cmd appops set <pkg> RUN_IN_BACKGROUND allow` | 无效 |
| 启动前台服务 | 无效。本 App 的服务只在播放时进入前台，进程仍是可冻结候选 |
| **先预热 Activity，再跑 instrument** | **无效**。`am instrument` 会杀掉并重建进程，预热好的 Activity 随之消失（日志里可见 `am_uid_stopped` → 新 `am_proc_start` → `wm_task_removed`） |
| 持续注入输入事件 | 无效 |

## 有效解法：在启动窗口内反复拉起宿主 Activity

```sh
am start -f 0x20000000 -n <pkg>/androidx.activity.ComponentActivity
```

要点有两处，缺一不可：

1. **必须反复戳，而不是只戳一次**。冻结发生在启动后约 5 秒，而"拉起 Activity"这个动作需要
   持续到测试自己接管为止。
2. **必须带 `FLAG_ACTIVITY_SINGLE_TOP`（`0x20000000`）**。不带的话每次戳都会叠一个新的
   `ComponentActivity` 实例；测试框架用的也是同一个类，多余实例会让使用
   `StateRestorationTester` 的那条测试报
   `No such compose hierarchies found in the app`。带上该标志则复用同一实例。

另外，**必须先唤醒并解锁**：屏幕休眠时 Activity 拿不到窗口（日志里是
`Focus leaving ... reason=NO_WINDOW`），进程照样被判定为后台而被冻。

`tools/run-instrumented-tests.sh` 已把这三步固化：

```sh
tools/run-instrumented-tests.sh                                   # 全部 45 项
tools/run-instrumented-tests.sh com.imankoppai.mediaanvil.Phase5MainThreadIoTest
```

（需要 `bash`/`sh`；Git for Windows 自带，用 `D:\Git\bin\bash.exe` 即可。）

## 验证结果

| 场景 | 结果 |
| --- | --- |
| Compose UI 测试（`MainNavigationTest`，2 项） | **连续 4 次** `OK (2 tests)` |
| 完整套件（45 项） | **连续 3 次** `OK (45 tests)` |
| 不干预 | 永久卡死，Activity 16 秒内从未出现 |

## 这不是 App 的缺陷

- 同样的代码在 Google 模拟器（CI）上**一直是通过的**，`instrumented-tests` job 为 success。
- 触发条件是厂商的后台冻结策略，与 App 逻辑无关。
- 排查中确认过：合并清单里 `androidx.activity.ComponentActivity` 声明正常
  （`exported="true"`），`ui-test-manifest` 也已在 `debugImplementation` 中，
  所以**不是**常见的"测试宿主没打进清单"那类问题。

## 如果将来在别的厂商设备上复现

按同样的顺序看三处即可定位：

1. `adb shell ps -A | grep <pkg>` —— 进程在不在
2. `adb shell cat /proc/<pid>/stat | cut -d' ' -f14,15` —— CPU 时间是否增长；不增长说明没被调度
3. `adb shell logcat -d -b events | grep -i freez` —— 有没有 `am_app_frozen`
