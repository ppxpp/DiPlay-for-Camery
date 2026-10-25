# 原生 NCM 编译开关

内核 `cdc_ncm` 网卡方案是可选构建能力，默认不集成。项目的 VPN/lwIP 仍需要 USB NCM 协议解析；关闭这里的原生网卡方案不会移除这些共用能力。

## 构建

项目根目录 `gradle.properties`：

```properties
diplay.enableNativeNcm=false
```

也可以在单次构建时覆盖同一个属性：

```sh
# 默认包：不集成原生 NCM
./gradlew :mobile:assembleStandaloneDebug -Pdiplay.enableNativeNcm=false

# 包含原生 NCM 的调试包
./gradlew :mobile:assembleStandaloneDebug -Pdiplay.enableNativeNcm=true
```

只接受小写 `true` 或 `false`；其他值会在配置阶段报错。Android Studio 修改属性后同步项目即可。该开关不是运行时设置，修改后必须重新构建 APK。

## 行为

| 构建 | 原生 NCM 实现和驱动 | 有线方案选择入口 | 默认方案 |
| --- | --- | --- | --- |
| Debug，开关关闭 | 不编译、不打包 | 原始 lwIP、优化 lwIP、VPN | VPN |
| Debug，开关开启 | 编译并打包 | 上述方案及原生 NCM | VPN |
| Release，开关关闭 | 不编译、不打包 | 隐藏 | VPN |
| Release，开关开启 | 编译并打包 | 隐藏 | VPN |

选择入口仍只在已验证的先锋车机上显示。Release 的 TCP 后端切换控制也会拒绝操作。运行时显式请求一个未编译的原生方案会报不可用，不会在连接失败后自动切换方案。

没有针对未发布旧选项的迁移/兼容逻辑。无有效选择时直接使用 VPN；有效的手动选择会保存。

## 源码边界

统一的 Gradle 配置是 `gradle/native-ncm.gradle`，供 `pioneer-adapter`、`shared`、`common` 读取同一个属性：

- `src/nativeNcm/`：内核模块装载、原生 helper/预热 worker、网卡租约与配置、专属设置资源；只有开启时加入编译。
- `src/withoutNativeNcm/`：轻量的禁用能力边界。不会启动线程、读取历史文件、加载驱动或修改网络；直接请求原生连接会报错。
- `src/nativeNcmTest/`、`src/withoutNativeNcmTest/`：对应配置的定向测试。
- `pioneer-adapter/src/nativeNcm/assets/camry_ncm/`：两个 `.ko`、装载工具、许可证；关闭时整个资产目录不进包。
- `pioneer-adapter/src/nativeNcm/consumer-rules.pro`：仅启用原生方案时提供 root 入口保留规则。

保留的公共能力：`PioneerRootCommand`、`PioneerRootGuard`、`PioneerRootTaskProtocol`、`PioneerInterfaceFirewall`、`PioneerFirmwareCatalog`，以及 `InterfaceSocketBinding`。它们服务于 VPN/USB 授权、root 初始化、接口防火墙和 socket 绑定，并不负责原生内核模块加载。

关闭包仍有少量能力枚举及禁用接口类型，用于主流程编译和明确拒绝不支持的请求；这不代表原生驱动/网卡实现仍被集成。隔离通过源码集完成，不依赖运行时 `if` 或 R8 删除不可达代码。

## 验证包内容

```sh
python3 scripts/check_native_ncm_apk.py \
  --apk mobile/build/outputs/apk/debug/mobile-debug.apk --enabled false

# 检查开启构建时将上面的 false 改为 true。
```

脚本检查 DEX 中真正定义的类与 ZIP 中的资产：关闭时禁止原生模块/helper/worker/预热实现，开启时要求它们齐全；两种构建都要求公共 VPN/root/socket 支持存在。切换开关后无需 `clean`，仍应检查新生成的 APK，避免拿错先前构建的文件。

本地构建和包检查不能替代实车连接验证。
