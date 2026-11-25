# 缇喵 vs 官方 EasyTier GUI 还原度审计

审计方式：只读源码对照，不改代码，不做运行期测试。  
路径缩写：

- `O` = `D:\extra\research\easytier-src`
- `T` = `D:\extra\timiao-et`

标记定义：

| 标记 | 含义 |
|---|---|
| ② | 缇喵界面可编辑，保存后生效 |
| ①③ | core 模型有；界面无；只能靠导入 TOML 或既有配置保留。未显示字段在保存时会丢失：新网络回落默认值，已有网络保留旧值（标 `*`） |
| ③ | 仅有 TOML passthrough；界面无 |
| ④ | 缺失，或保存时被丢弃 |
| ②⚠ | 界面能输入，但语义/格式与官方不一致 |

---

## 0. 总体判断

- IPv4、单网络、基础组网路径可用。节点表有主要列。
- 官方 GUI 的配置面只还原了一个子集。VPN portal、TOML 原文编辑、配置服务器、本机信息/事件日志/WireGuard 设备缺口大。
- 连接质量的主要差异在 Android TUN 层：DNS、IPv6、MTU、TUN 生命周期、路由同步、重连。
- 官方当前 GUI 源码没有“公共服务器选择列表”。只有 `public_server_url` 模型字段，且归一化时折叠为 `peer_urls`。缇喵没有该字段，但其“服务器列表”等价于官方 Manual 模式。此项不构成功能缺口。

本次只有源码证据，不做运行测试，因此不给百分比。

| 面 | 还原程度 | 主要缺口 |
|---|---|---|
| 基础配置 | 大部分字段 | secure mode、credential、hostname、默认网络名/监听器 |
| 高级 flags | 约一半 | 15/31 开关；16 项界面无，导入保存也不可靠 |
| 高级其他 | 部分 | mtu、限速、socks5、manual routes、exit nodes、mapped listeners |
| 端口转发 | 功能有，交互不一致 | 官方结构化行不被接受，非法行静默丢弃 |
| ACL | 仅原文 passthrough | 无结构化编辑/校验 |
| VPN portal | 基本未还原 | 无开关/私钥/客户端/设备配置 |
| 状态与节点表 | 节点主列可用 | 本机信息、事件日志、WireGuard 设备、若干口径 |
| 连接质量 | IPv4 单网络可用 | DNS、IPv6、MTU、共享 TUN、reconcile、重连 |

---

## 1. 配置项对照

官方字段来源：`O/easytier-web/frontend-lib/src/components/Config.vue`、`O/easytier-web/frontend-lib/src/types/network.ts`、`O/easytier-proto/proto/api_manage.proto`、`O/easytier-proto/proto/common.proto`。  
缇喵来源：`T/core/src/main/java/app/timiao/et/core/NetworkConfig.kt`、`T/app/src/main/java/app/timiao/et/ui/data/UiNetwork.kt`、`T/app/src/main/java/app/timiao/et/ui/screens/NetworkEditScreen.kt`。

### 1.1 基础页

| 官方字段 | 官方行为/默认 | 缇喵 | 证据 |
|---|---|---|---|
| `dhcp` | 默认 true；勾选后 IPv4 输入禁用 | ② 开关 | 官方 `Config.vue:295-313`；缇喵 `NetworkEditScreen.kt:200-219` |
| `virtual_ipv4` | 默认空；静态地址 | ② 文本，写 `ipv4` | 官方 `network.ts:193`；缇喵 `UiNetwork.kt:93` |
| `network_length` | 默认 24，独立输入 1-32 | ② 合并在 IPv4 文本的 `/前缀`；无独立字段 | 官方 `network.ts:194`、`Config.vue:309-311`；缇喵 `JniEngine.kt:185-190` |
| `network_name` | 默认 `easytier` | ②；新建默认空 | 官方 `network.ts:195`；缇喵 `UiNetwork.kt:114-118` |
| `secure_mode` | 开关；Noise 身份 | ①③* core 有；界面无 | 官方 `Config.vue:79-84,320-324`；缇喵 `NetworkConfig.kt:76-80`、`UiNetwork.kt:65-101` |
| `network_secret` | 默认空 | ② | 官方 `network.ts:196`；缇喵 `NetworkEditScreen.kt:177-186` |
| `credential_secret` | 凭证模式，等价 CLI `--credential` | ④ 无表单字段；只有 ManagedCredential 列表模型 | 官方 `network.ts:84-88,404-436`；缇喵 `NetworkConfig.kt:82-90` |
| `peer_urls` / 初始节点 | 多行 URL；协议 tcp/udp/wg/ws/wss/quic/faketcp/http/https/txt/srv | ② 多行文本；无协议校验/补全 | 官方 `Config.vue:356-363`、`network.ts:45-57`；缇喵 `NetworkEditScreen.kt:187-199`、`UiNetwork.kt:91` |
| `networking_method` | 模型字段；GUI 归一化后总是 Manual | ④ 无（官方 GUI 不暴露，非缺口） | 官方 `network.ts:199,345-375`；缇喵 `NetworkConfig.kt:4-44` 无此字段 |
| `public_server_url` | 模型字段；GUI 无输入；会被折叠为 peer_urls | ④ 无（官方 GUI 不暴露，非缺口）；服务器列表可覆盖同一用途 | 官方 `network.ts:99-100,355-374`；`Config.vue` 无该控件；缇喵 `NetworkConfig.kt:4-44` 无 |
| `advanced_settings` | 模型字段；GUI 无控件 | ④ 无（官方 GUI 不暴露，非缺口） | 官方 `network.ts:205`；缇喵 `NetworkConfig.kt:4-44` 无 |

### 1.2 高级页：布尔 flags

官方向 `formFlags()` 取 schema 中非 deprecated、API 可承载的布尔项，再加 `ipv6_public_addr_auto`，共 31 个开关。  
证据：`O/easytier-web/frontend-lib/src/types/network.ts:132-183`、`Config.vue:128-133,376-387`、`O/easytier-proto/proto/common.proto:9-94`。

缇喵界面只放 15 个开关：`T/app/src/main/java/app/timiao/et/ui/data/UiNetwork.kt:35-44,121-143`。core `Flags` 模型有上游全部字段：`T/core/src/main/java/app/timiao/et/core/NetworkConfig.kt:93-138`。

已映射的 15 项：

| 官方 API 字段 | 缇喵 key | 标记 | 说明/证据 |
|---|---|---|---|
| `latency_first` | `latency_first` | ② | `UiNetwork.kt:123` |
| `multi_thread` | `multi_thread` | ② | `UiNetwork.kt:124`；官方默认 true |
| `disable_p2p` | `disable_p2p` | ② | `UiNetwork.kt:125` |
| `disable_udp_hole_punching` | `disable_udp_hole_punching` | ② | `UiNetwork.kt:126` |
| `disable_encryption` | `enable_encryption` | ② | 语义反向：官方勾选=禁用加密；缇喵勾选=启用加密。官方 `network.ts:132-141`；缇喵 `UiNetwork.kt:38,127,109` |
| `disable_ipv6` | `enable_ipv6` | ② | 语义反向；运行行为等价 |
| `enable_exit_node` | `enable_exit_node` | ② | `UiNetwork.kt:140` |
| `no_tun` | `no_tun` | ②⚠ | 可勾选，但 TUN 层未处理。见 3 节 |
| `use_smoltcp` | `use_smoltcp` | ② | `UiNetwork.kt:132` |
| `relay_all_peer_rpc` | `relay_all_peer_rpc` | ② | `UiNetwork.kt:134` |
| `enable_kcp_proxy` | `enable_kcp_proxy` | ② | `UiNetwork.kt:137` |
| `enable_quic_proxy` | `enable_quic_proxy` | ② | `UiNetwork.kt:138` |
| `proxy_forward_by_system` | `proxy_forward_by_system` | ② | `UiNetwork.kt:141` |
| `enable_magic_dns` | `accept_dns` | ②⚠ | UI 可开；但 TUN 不加 DNS/路由。见 3 节 |
| `enable_private_mode` | `private_mode` | ② | `UiNetwork.kt:133` |

官方有、缇喵界面无的 16 项（core 模型有，故标 ①③*）：

| 官方字段 | 缇喵 core | 证据 |
|---|---|---|
| `bind_device` | `bindDevice`，默认 true | 官方 `common.proto:26`；缇喵 `NetworkConfig.kt:109` |
| `disable_kcp_input` | `disableKcpInput` | `NetworkConfig.kt:111` |
| `disable_quic_input` | `disableQuicInput` | `NetworkConfig.kt:117` |
| `disable_sym_hole_punching` | `disableSymHolePunching` | `NetworkConfig.kt:124` |
| `p2p_only` | `p2pOnly` | `NetworkConfig.kt:125` |
| `disable_tcp_hole_punching` | `disableTcpHolePunching` | `NetworkConfig.kt:126` |
| `lazy_p2p` | `lazyP2p` | `NetworkConfig.kt:127` |
| `need_p2p` | `needP2p` | `NetworkConfig.kt:128` |
| `disable_upnp` | `disableUpnp` | `NetworkConfig.kt:130` |
| `disable_relay_data` | `disableRelayData` | `NetworkConfig.kt:131` |
| `enable_udp_broadcast_relay` | `enableUdpBroadcastRelay` | `NetworkConfig.kt:132` |
| `prefer_peer_relay` | `preferPeerRelay` | `NetworkConfig.kt:134` |
| `enable_udp_fec` | `enableUdpFec` | `NetworkConfig.kt:135` |
| `enable_udp_fec_kcp` | `enableUdpFecKcp` | `NetworkConfig.kt:136` |
| `enable_udp_fec_quic` | `enableUdpFecQuic` | `NetworkConfig.kt:137` |
| `ipv6_public_addr_auto` | `ipv6PublicAddrAuto`（非 Flags） | 官方 `Config.vue:132`；缇喵 `NetworkConfig.kt:17` |

`①③*` 的 `*`：现有网络保存会用 `repository.get(id)` 作 base，未显示字段能保留（`T/app/src/main/java/app/timiao/et/ui/AppViewModel.kt:99-101`）。  
但导入 TOML 时 `toUi()` 先丢掉未显示字段（`UiNetwork.kt:65-80`）。新网络保存时 base 为 null，未显示字段回落默认值；已有网络保存时 base 是旧配置，未显示字段保留旧值，不会采用导入值（`AppViewModel.kt:110-112`、`UiNetwork.kt:83-101`、`NetworkEditScreen.kt:429`）。

### 1.3 高级页：非 flags 字段

| 官方字段 | 官方行为 | 缇喵 | 证据 |
|---|---|---|---|
| `hostname` | 文本 | ①③* 界面无 | 官方 `Config.vue:390-395`；缇喵 `NetworkConfig.kt:7` |
| `proxy_cidrs` | 列表；支持 `real->mapped` | ②⚠ 只收 CIDR；无法输入 mapped/allow，改动该行会丢原值 | 官方 `Config.vue:398-405`、`api_input.rs:32-55`；缇喵 `UiNetwork.kt:73,94`、`TomlCodec.kt:171-173` |
| `vpn_portal_config.enabled` | 开关 | ④ 无开关 | 官方 `Config.vue:409-411,245-258`；缇喵 `NetworkConfig.kt:57-62` |
| `vpn_portal_config.wireguard_listen` | `host:port`，默认 `0.0.0.0:22022` | ②⚠ 输入框占位符是 CIDR；会写出非法值 | 官方 `vpnPortal.ts:10-13`、`Config.vue:416-419`；缇喵 `NetworkEditScreen.kt:285-293`、`UiNetwork.kt:96` |
| `vpn_portal_config.wireguard_private_key` | 自动生成 X25519 | ④ 不生成；core 有字段 | 官方 `Config.vue:204-207,249-255`、`vpnPortal.ts:6-15`；缇喵 `TomlCodec.kt:252-254` |
| `vpn_portal_config.clients` | 名称/IP/groups，可加删 | ①③* core 有；界面无 | 官方 `Config.vue:275-283,421-449`；缇喵 `NetworkConfig.kt:64-68` |
| `listener_urls` | 多行 URL，默认三条 | ②；新建默认空 | 官方 `Config.vue:452-458`、`network.ts:207-212`；缇喵 `NetworkEditScreen.kt:306-318`、`UiNetwork.kt:114-118` |
| `dev_name` | 文本 | ①③* Flags.devName；界面无 | 官方 `Config.vue:460-466`；缇喵 `NetworkConfig.kt:94` |
| `mtu` | 400-1380，默认空→1380 | ①③* Flags.mtu；界面无 | 官方 `Config.vue:468-477`、`common.proto:13`；缇喵 `NetworkConfig.kt:98` |
| `instance_recv_bps_limit` | 字节/秒，留空不限 | ①③* Flags；界面无 | 官方 `Config.vue:479-490`；缇喵 `NetworkConfig.kt:129` |
| `enable_relay_network_whitelist` + `relay_network_whitelist` | 开关 + 列表；`*`=全部；启用白名单但列表为空=禁止转发 | ②⚠ 只有列表；无 enable 开关；空列表被转成 `*`（全部）；无法表达“启用且为空=禁止转发”；`*` 会被 UI 清空 | 官方 `Config.vue:492-509`、`api_input.rs:103-111`、`cn.yaml:265-269`；缇喵 `UiNetwork.kt:76,87` |
| `enable_manual_routes` + `routes` | 开关 + 列表 | ①③* `routes` core 有；界面无 | 官方 `Config.vue:511-527`；缇喵 `NetworkConfig.kt:26` |
| `enable_socks5` + `socks5_port` | 开关 + 端口 1080 | ①③* `socks5Proxy` 字符串；界面无 | 官方 `Config.vue:529-544`；缇喵 `NetworkConfig.kt:28` |
| `exit_nodes` | 列表 | ①③* 界面无 | 官方 `Config.vue:546-556`；缇喵 `NetworkConfig.kt:27` |
| `mapped_listeners` | 列表 | ①③* 界面无 | 官方 `Config.vue:558-567`；缇喵 `NetworkConfig.kt:21` |

### 1.4 端口转发

| 官方 | 缇喵 | 标记 | 证据 |
|---|---|---|---|
| 行编辑：`proto` 选择 tcp/udp，`bind_ip`、`bind_port`、`dst_ip`、`dst_port` | 一行文本：`tcp://bind -> dst`；保存时正则解析 | ②⚠ | 官方 `Config.vue:574-659`、`network.ts:584-597`；缇喵 `UiNetwork.kt:74,103-106`、`NetworkEditScreen.kt:272-284` |
| TOML 写 `bind_addr`/`dst_addr`/`proto` | TOML 写同样三个字段，proto 为 `tcp`/`udp` | 一致 | 官方 `api_input.rs:364-385`；缇喵 `TomlCodec.kt:184-196` |

风险：官方帮助示例是 `udp://0.0.0.0:12345/10.126.126.1:23456`（`O/easytier-web/frontend-lib/src/locales/cn.yaml:281-283`）。缇喵只接受 `->` 格式。用户粘贴官方格式时，`parseForward` 返回 null，保存时该行被静默丢弃（`UiNetwork.kt:95,103-106`）。

### 1.5 ACL

| 官方 | 缇喵 | 标记 | 证据 |
|---|---|---|---|
| 结构化编辑器：ACL v1 group、chain、rule；启用按钮 | 单个文本域；按 `[acl...]` 整段截取并原样导出 | ③ | 官方 `Config.vue:663-671`、`AclManager.vue:1-119`、`network.ts:258-315`；缇喵 `NetworkEditScreen.kt:330-340`、`TomlCodec.kt:579-613` |
| 默认无 ACL | 默认空 | 一致 | 官方 `network.ts:114-121,227`；缇喵 `NetworkConfig.kt:37` |

说明：`TomlAcl.split` 取 section 首段，`[acl.acl_v1...]` 也会被 `"acl"` 捕获（`TomlCodec.kt:587-611`）。因此官方 ACL TOML 能原样保留。但缇喵没有结构编辑、校验、分组选择，无法生成官方 GUI 的规则。输入框占位符 `in 127.0.0.1/32 drop`（`NetworkEditScreen.kt:335`）是旧式文本，不是当前 `acl.acl_v1` TOML（`toml.rs:1825`）；直接输入会让导出配置无法解析。

### 1.6 VPN portal

| 官方 | 缇喵 | 标记 | 证据 |
|---|---|---|---|
| 启用开关、WireGuard 监听、自动私钥、客户端列表、每客户端独立配置、二维码/下载 | core 有模型；界面只有一个“VPN portal”字符串，占位符是 CIDR | ②⚠ / ④ | 官方 `Config.vue:407-449`、`vpnPortal.ts:6-51`、`VpnPortalDialog.vue:1-295`；缇喵 `NetworkEditScreen.kt:285-293`、`NetworkConfig.kt:57-68`、`TomlCodec.kt:242-274` |
| 客户端配置由节点生成，前端只替换 Endpoint | 无生成逻辑 | ④ | 官方 `vpnPortal.ts:48-51`、`VpnPortalDialog.vue:162-175` |

风险：`wireguard_listen` 必须是 `host:port`。官方文档占位符是 `0.0.0.0:22022`（`O/easytier-web/frontend-lib/src/locales/cn.yaml:54-55`）。缇喵占位符是 `10.14.14.0/24`（`NetworkEditScreen.kt:290`）。用户按占位符输入会写出无法解析的 portal 监听地址（`api_input.rs:397-402`）。

### 1.7 公共服务器 / 配置服务器

| 项 | 官方 | 缇喵 | 标记 | 证据 |
|---|---|---|---|---|
| 公共服务器列表 | 当前 GUI 源码无列表、无选择页；仅 `public_server_url` 模型，归一化时折叠为 `peer_urls` | 无该概念；服务器列表等价 Manual 模式 | 无缺口 | 官方 `network.ts:99-100,345-375`；`Config.vue:291-567` 无该控件；`NetworkList.vue:38-43,111-136` 只创建 Manual/Standalone |
| 配置服务器 URL | 设置菜单“配置服务器”，存于本机 mode 配置，不写 NetworkConfig/TOML | 无 UI；JNI 有 `startConfigServerClient` 声明，但 app 未调用 | ④ | 官方 `easytier-gui/src/pages/index.vue:46-47,470-474,576-589`；缇喵 `EasyTierJNI.kt:39-66`、`AppViewModel.kt:27-145` |
| 服务模式选项 | `config_dir`、`file_log_dir`、`file_log_level`、`rpc_portal` | 无 | ④ | 官方 `index.vue:178-200`、`ModeSwitcher.vue:26-36` |

### 1.8 默认值对照

官方默认：`O/easytier-web/frontend-lib/src/types/network.ts:185-229`、`common.proto:9-94`。  
缇喵默认：`T/app/src/main/java/app/timiao/et/ui/data/UiNetwork.kt:108-118`、`T/core/src/main/java/app/timiao/et/core/NetworkConfig.kt:93-138`。

| 项 | 官方 GUI | 缇喵 | 结果 |
|---|---|---|---|
| 网络名 | `easytier` | 新建空；core 默认 `default` | 不一致 |
| DHCP | true | true | 一致 |
| 监听器 | tcp/udp 11010、wg 11011 三条 | 空 | 不一致，影响入站直连 |
| 网络密钥 | 空 | 空 | 一致 |
| MTU | 空→core 1380；Android TUN 固定 1300 | core 1380；Android TUN 1380 | 不一致，见 3 节 |
| 加密 | 默认开（`disable_encryption=false`） | 默认开（`enable_encryption=true`） | 有效值一致，UI 语义反向 |
| IPv6 | 默认开 | 默认开 | 一致 |
| 多线程 | 默认开 | 默认开 | 一致 |
| `bind_device` | 默认开 | core 默认开，界面无 | 有效值一致 |
| `relay_network_whitelist` | core `*`；UI enable=false | core `*`；UI 显示空 | 运行一致，UI 语义不清 |
| `instance_recv_bps_limit` | 空（不限） | null（不限） | 一致 |
| 端口转发/ACL/routes/exit nodes/mapped listeners | 空 | 空 | 一致 |

---

## 2. 状态与节点表对照

官方节点表：`O/easytier-web/frontend-lib/src/components/Status.vue:424-455`。  
缇喵：`T/core/src/main/java/app/timiao/et/core/StatusParser.kt:27-108`、`T/app/src/main/java/app/timiao/et/ui/screens/PeersScreen.kt:91-103,200-242`、`HomeScreen.kt:61-78,156-215`。

| 官方展示项 | 缇喵 | 数据口径 | 证据 |
|---|---|---|---|
| 虚拟 IPv4 | ② 列表和详情 | 同为 `ip/prefix` | 官方 `Status.vue:102-107`；缇喵 `StatusParser.kt:94,138-150` |
| 主机名 | ② | 一致；官方有公网服务器/中继标签 | 官方 `Status.vue:426-442,257-263`；缇喵 `StatusParser.kt:93`、`PeersScreen.kt:296-306` |
| 路由 cost | ② | 规则一致：`local`/`p2p`/`relay(N)` | 官方 `Status.vue:37-44`；缇喵 `StatusParser.kt:95-99`、`Formatters.kt:35-41` |
| 协议 | ② 但只在详情显示 | 官方用 tunnel local_addr 判断 IPv6 并加 `6`；缇喵只取 `tunnel_type` | 官方 `Status.vue:109-133`；缇喵 `StatusParser.kt:79-81` |
| 延迟 | ② 列表/详情 | 官方优先 `default_conn_id` 的连接，否则取最小；缇喵取所有连接最小。两者都向上取整到毫秒 | `statusDisplay.ts:61-83`；`StatusParser.kt:76-101`；`PeersScreen.kt:234` |
| 上行/下行字节 | ② 只在详情 | 都是所有连接 `tx_bytes`/`rx_bytes` 求和 | 官方 `Status.vue:51-96`；缇喵 `StatusParser.kt:74-75`、`PeersScreen.kt:240-241` |
| 丢包率 | ② | 官方优先默认连接；缇喵取第一个可解析值 | 官方 `statusDisplay.ts:85-95`；缇喵 `StatusParser.kt:82-84` |
| NAT 类型 | ② | 官方映射完整英文名；缇喵保留枚举名，中文只覆盖 open/cone/symmetric | 官方 `statusDisplay.ts:3-14,97-104`；缇喵 `StatusParser.kt:85-90,113-124`、`Formatters.kt:45-51` |
| 版本 | ② 详情 | 官方空值显示 `unknown`；缇喵空字符串 | 官方 `Status.vue:98-100,450-454`；缇喵 `StatusParser.kt:106` |
| 节点数 | ② | 官方计数含本机行；缇喵不含 | 官方 `Status.vue:21-35,265-270`；缇喵 `PeersScreen.kt:102-103,113` |
| 本机公网 IP / 本地 IP / 监听 / STUN / Peer ID / TUN 名 | ④ | StatusParser 只读 `my_node_info.virtual_ipv4` | 官方 `Status.vue:147-230`；缇喵 `StatusParser.kt:30-32,53-56` |
| 事件日志 | ④ | 不解析 `events` | 官方 `Status.vue:331-355`；缇喵 `StatusParser.kt:27-50` |
| WireGuard 设备 | ④ | 无状态接口、无对话框 | 官方 `Status.vue:299-330,357-358,402`；缇喵 `NetworkConfig.kt:57-68` 仅配置 |
| 全局速率 | ② | 官方 2 秒窗口、KiB；缇喵 1 秒窗口、十进制 KB | 官方 `Status.vue:272-292`、`humanFileSize` 68-86；缇喵 `StatusParser.kt:58-69`、`Formatters.kt:7-15` |
| 流量图 | ④ | 无 | 官方 `Status.vue:381-384`、`NetworkChart.vue:1-249` |
| 错误信息 | ② | 都有 `error_msg` | 官方 `Status.vue:360-371`；缇喵 `StatusParser.kt:32-35`、`HomeScreen.kt:167-178` |

---

## 3. 连接质量对照（Android TUN）

官方 Android 实现：`O/tauri-plugin-vpnservice/android/src/main/java/TauriVpnService.kt`、`O/easytier-gui/src/composables/mobile_vpn.ts`、`O/easytier/src/instance/factory/mobile_tun.rs`。  
缇喵：`T/app/src/main/java/app/timiao/et/service/TimiaoVpnService.kt`、`T/core/src/main/java/app/timiao/et/core/JniEngine.kt`。

| 项 | 官方 Android | 缇喵 | 影响 | 证据 |
|---|---|---|---|---|
| TUN MTU | 请求固定 1300 | 用 `flags.mtu`，默认 1380；仅越界时回落 1300 | 移动网络下更易分片/丢包 | 官方 `mobile_vpn.ts:231-237`、`TauriVpnService.kt:191,208`；缇喵 `JniEngine.kt:73`、`NetworkConfig.kt:98`、`TimiaoVpnService.kt:88` |
| DNS | 有 magic DNS 时 `dns=100.100.100.101`，并加 `100.100.100.101/32` 路由；Builder 调 `addDnsServer` | 完全不设 DNS，不加 magic DNS 路由 | `.et.net` 域名不可用；DNS 可能绕过 TUN | 官方 `mobile_tun.rs:119-125`、`TauriVpnService.kt:209`；缇喵 `TimiaoVpnService.kt:82-99` 无 `addDnsServer` |
| IPv6 | `allowFamily(AF_INET6)`；加 IPv6 地址和路由，含 `::/0` | 不调 `allowFamily`；只加 IPv4 地址/路由；IPv6 CIDR 走 `addRoute(String,int)` 且异常被吞 | IPv6 不可用或被绕过；`::/0` 静默失效 | 官方 `TauriVpnService.kt:206`、`mobile_tun.rs:105-118,316-366`；缇喵 `TimiaoVpnService.kt:82-99`、`JniEngine.kt:72` |
| `setBlocking` | false | false | 一致 | 官方 `TauriVpnService.kt:189`；缇喵 `TimiaoVpnService.kt:87` |
| `allowFamily` | `AF_INET6` | 无 | 差异 | 官方 `TauriVpnService.kt:206`；缇喵 `TimiaoVpnService.kt:85-89` |
| `isMetered` | API 29+ false | API 29+ false | 一致 | 官方 `TauriVpnService.kt:221-225`；缇喵 `TimiaoVpnService.kt:96` |
| `addDisallowedApplication` | 排除自身包名 | 排除自身包名 | 一致 | 官方 `TauriVpnService.kt:217-219`、`mobile_vpn.ts:235`；缇喵 `TimiaoVpnService.kt:95` |
| 多实例共享 TUN | 一个 fd 挂到多个实例；地址/路由取并集；冲突实例拒绝 | 每次 start 新建一个 TUN；上一个 PFD 已 `detachFd`，`close()` 不可靠；没有并集/冲突处理 | 多个网络不共享 TUN；旧 fd 可能泄漏；无冲突检测 | 官方 `mobile_tun.rs:57-131,221-251`；缇喵 `TimiaoVpnService.kt:82-99`、`JniEngine.kt:73` |
| 2 秒同步 | 每 2 秒 reconcile 运行实例、地址、路由、DNS；配置变化则停/启 VPN | 只在 start 时建立一次，之后不重算 TUN 配置 | DHCP/路由变化后系统路由不更新 | 官方 `mobile_vpn.ts:22,327-469`；缇喵 `JniEngine.kt:61-91` |
| 重连策略 | 查询失败/未就绪时重试，最多 60 次×2 秒；有 RPC watchdog | 轮询异常只把状态置 Error；不重启核心；服务 `START_NOT_STICKY` | 核心异常后不会自愈 | 官方 `mobile_vpn.ts:22-23,133-160,443-468`、`index.vue:353-391`、`TauriVpnService.kt:64`；缇喵 `JniEngine.kt:153-177`、`TimiaoVpnService.kt:78` |
| `no_tun` | 运行实例过滤 `no_tun`，不发 TUN；另有保活前台服务 | 仍建立 TUN 并 `setTunFd` | 勾选 no_tun 无效 | 官方 `runtime_host.rs:150-152`、`mobile_vpn.ts:503-523`；缇喵 `UiNetwork.kt:139`、`JniEngine.kt:71-74` |
| DHCP 与静态地址 | `dhcp` 为真时等 DHCP；为假才用静态 | `ipv4` 非空就直接用，不看 `dhcp`；为空则最多等 15 秒 | 打开 DHCP 后残留静态 IP 会拿到错误 TUN 地址；慢 DHCP 直接失败 | 官方 `mobile_vpn.ts:383-393`、`instance/config.rs:235-237`、`api_input.rs:303-316`；缇喵 `JniEngine.kt:42,142-150`、`UiNetwork.kt:93` |
| 路由来源 | 运行时 peer proxy CIDR、public IPv6 路由、地址网段 | 启动时静态 `proxyNetworks` + `routes` + 地址网段 | 对端新增代理网段后系统路由不更新 | 官方 `virtual_nic.rs:1620-1654`、`cidr_monitor.rs:99-130`；缇喵 `JniEngine.kt:72` |
| 保活 | TUN 前台服务 + 无 TUN 时 MainForegroundService | 只有 TUN 前台服务 | `no_tun` 场景无保活 | 官方 `TauriVpnService.kt:108-160`；缇喵 `TimiaoVpnService.kt:38-52` |
| 状态轮询 | 1 秒 | 1 秒 | 一致 | 官方 `RemoteManagement.vue:440-449`；缇喵 `JniEngine.kt:41,153-165` |

---

## 4. 缺口清单（按严重度）

### 严重

| # | 缺口 | 建议修法 | 证据 |
|---|---|---|---|
| S1 | Magic DNS 完全未接入 TUN。`accept_dns` 可开，但 Android 无 DNS 服务器、无 magic IP 路由，`*.et.net` 不解析；DNS 可能泄漏到物理网卡。 | 在 `establishTun` 增加 `addDnsServer("100.100.100.101")` 和 `addRoute("100.100.100.101", 32)`；条件与官方一致，取 accept_dns 且本机有 IPv4。core 配置增加 dns 字段解析。 | 官方 `mobile_tun.rs:119-125`、`TauriVpnService.kt:209`；缇喵 `TimiaoVpnService.kt:82-99`、`StatusParser.kt:27-56` |
| S2 | IPv6 未接入 TUN。没有 `allowFamily(AF_INET6)`，没有 IPv6 地址/路由；IPv6 CIDR 静默失效。 | 使用 `addAddress(InetAddress,prefix)`、`addRoute(InetAddress,prefix)`；加 `allowFamily(AF_INET6)`；解析 `my_node_info.virtual_ipv6` 与 IPv6 路由。 | 官方 `TauriVpnService.kt:201-215,206`、`mobile_tun.rs:105-118`；缇喵 `TimiaoVpnService.kt:82-99`、`JniEngine.kt:72` |
| S3 | TOML 原文编辑不生效。输入只存 `UiNetwork.tomlText`，`toConfig()` 不读它；界面文案“保存后优先使用”与行为不符。 | 让保存优先解析 TOML 原文；或删除该入口。解析失败要报错，不能静默忽略。 | `NetworkEditScreen.kt:320-353`、`UiNetwork.kt:29,83-101`、`AppViewModel.kt:99-101` |
| S4 | 从界面无法完成 VPN portal 配置：无启用开关、无私钥生成、无客户端、无设备状态/配置/二维码；输入框占位符还是 CIDR，会生成非法 `wireguard_listen`。 | 按官方模型实现 `enabled`、`wireguard_listen`（默认 `0.0.0.0:22022`）、X25519 私钥、clients CRUD、客户端配置/二维码；增加 portal 状态接口。 | 官方 `Config.vue:204-258,275-283,407-449`、`vpnPortal.ts:6-51`、`VpnPortalDialog.vue:1-295`；缇喵 `NetworkEditScreen.kt:285-293`、`UiNetwork.kt:96`、`TomlCodec.kt:242-274` |

### 中

| # | 缺口 | 建议修法 | 证据 |
|---|---|---|---|
| M1 | Android TUN 默认 MTU 1380，官方固定 1300。 | Android TUN 固定 1300；core MTU 与 TUN MTU 分开。 | 官方 `mobile_vpn.ts:236`；缇喵 `NetworkConfig.kt:98`、`TimiaoVpnService.kt:88` |
| M2 | 新建网络默认没有监听器；官方默认 tcp/udp 11010、wg 11011。 | `newNetwork()` 写入官方三条默认监听器。 | 官方 `network.ts:207-212`；缇喵 `UiNetwork.kt:114-118` |
| M3 | 没有 VPN reconcile。DHCP 地址、代理网段、DNS 变化后 TUN 不更新。 | 增加 2 秒 reconcile：读运行实例、地址、路由、DNS；变化时重建 TUN。 | 官方 `mobile_vpn.ts:22,327-469`；缇喵 `JniEngine.kt:61-91` |
| M4 | 无自愈：轮询失败只显示 Error；服务非 sticky。 | 加核心重启/重连退避；服务改 `START_STICKY` 并处理无 Intent 重启。 | 官方 `mobile_vpn.ts:133-160,443-468`、`TauriVpnService.kt:41-65`；缇喵 `JniEngine.kt:153-177`、`TimiaoVpnService.kt:78` |
| M5 | `no_tun` 无效：仍建立 TUN。 | 启动前检查 `flags.noTun`；为 true 时跳过 `establishTun`/`setTunFd`，并保留前台服务。 | 官方 `runtime_host.rs:150-152`、`mobile_vpn.ts:503-523`；缇喵 `UiNetwork.kt:139`、`JniEngine.kt:71-74` |
| M6 | DHCP 与静态地址选择错误：DHCP 为真仍优先用残留 `ipv4`；静态为空时等 15 秒后失败。core 在 DHCP 下会忽略 `ipv4`，TUN 却用了它。 | `resolveAddress` 先看 `dhcp`：为真只等 DHCP；为假才用 `ipv4`，为空立即报配置错误。 | `JniEngine.kt:142-150`；官方 `mobile_vpn.ts:383-393`、`instance/config.rs:235-237` |
| M7 | 多网络没有共享 TUN：每次 start 新建 TUN；上一个 fd 已 detach，`close()` 不可靠；无地址/路由并集、无冲突拒绝。 | 保留一个 TUN fd，挂多个实例；地址/路由并集；冲突实例拒绝并提示。官方 JNI 已支持。 | 官方 `mobile_tun.rs:57-131,221-251`；缇喵 `TimiaoVpnService.kt:82-99`；`docs/ARCHITECTURE.md:62` 仍列为 Phase 2 |
| M8 | 路由只取启动时静态配置，缺 peer 代理网段和动态 proxy CIDR。 | 从状态/运行时读取 proxy CIDR，加入 TUN 路由并随 reconcile 更新。 | 官方 `virtual_nic.rs:1620-1654`、`cidr_monitor.rs:99-130`；缇喵 `JniEngine.kt:72` |
| M9 | 导入 TOML 后保存丢字段：hostname、secure_mode、managed_credentials、dhcpv6、routes、exit_nodes、socks5、portal clients、未显示 flags 等在 `toUi()` 被丢弃；新网络回落默认，已有网络保留旧值。 | 保存时保留导入的完整 `NetworkConfig` 作为 base；或界面编辑只改差异字段。 | `AppViewModel.kt:110-112`、`UiNetwork.kt:65-101`、`NetworkEditScreen.kt:429` |
| M10 | 高级配置界面缺口大：hostname、mtu、instance limit、socks5、manual routes、exit nodes、mapped listeners、15 个 flags + `ipv6_public_addr_auto`。 | 按官方 `Config.vue` 补齐；flags 用 schema 默认值渲染，避免“未设置=关闭”。 | 官方 `Config.vue:376-567`、`network.ts:154-183`；缇喵 `UiNetwork.kt:121-143` |
| M11 | 端口转发只认 `tcp://bind -> dst`；官方格式的行会被静默丢弃。 | 改为官方结构化行编辑；或同时接受 `/dst` 格式，解析失败阻止保存并提示。 | `UiNetwork.kt:103-106`；官方 `Config.vue:574-659`、`cn.yaml:281-283` |
| M12 | 子网代理 `mapped` 语法不可编辑：无法输入 `real->mapped`/`allow`，改动该行会丢原值。 | UI 直接编辑 `real->mapped` 字符串；保存时解析成 `ProxyNetwork`。 | 官方 `api_input.rs:32-55`；缇喵 `UiNetwork.kt:73,94`、`TomlCodec.kt:171-173` |
| M13 | 状态页缺本机公网 IP、监听、STUN、Peer ID、TUN 名、事件日志、WireGuard 设备。 | 扩展 `StatusParser` 读 `my_node_info.ips/listeners/stun_info/peer_id`、`dev_name`、`events`；增加事件时间线。 | 官方 `Status.vue:147-230,331-355,357-358`；缇喵 `StatusParser.kt:27-56` |
| M14 | 默认网络名不一致：官方 `easytier`，缇喵新建空、core 默认 `default`。 | 新建默认 `easytier`。 | 官方 `network.ts:195`；缇喵 `UiNetwork.kt:114-118`、`NetworkConfig.kt:8` |
| M15 | 配置服务器缺失：JNI 已有声明，app 无 UI/调用。 | 增加配置服务器 URL 设置与 `startConfigServerClient` 生命周期；或明确不做。 | 官方 `index.vue:46-47,470-474,576-589`；缇喵 `EasyTierJNI.kt:39-66` |
| M16 | ACL 只有原文，无结构化编辑；占位符是旧式文本，不是当前 `acl.acl_v1` TOML，直接输入会导致导出配置无法解析。 | 接入官方 ACL v1 结构（groups/chains/rules）；至少提供格式校验，禁止保存非法 ACL。 | 官方 `Config.vue:663-671`、`network.ts:258-315`、`toml.rs:1825`；缇喵 `NetworkEditScreen.kt:330-340`、`TomlCodec.kt:579-613` |
| M17 | relay whitelist 语义缺失：无 enable 开关；空列表被转成 `*`（全部），无法表达官方“启用白名单且列表为空=禁止转发”；`*` 又被 UI 清空。 | 加官方 enable 开关；区分“未启用=全部”“启用且空=禁止转发”“`*`=全部”；`*` 不要从 UI 清掉。 | 官方 `Config.vue:492-509`、`api_input.rs:103-111`、`cn.yaml:265-269`；缇喵 `UiNetwork.kt:76,87` |

### 低

| # | 缺口 | 建议修法 | 证据 |
|---|---|---|---|
| L1 | 延迟取所有连接最小值，官方优先 default conn。 | 解析 `default_conn_id`，优先它；无默认连接时再取最小。 | `statusDisplay.ts:61-83`；`StatusParser.kt:76-101`、`PeersScreen.kt:234` |
| L2 | 丢包不优先 default conn。 | 与 L1 同。 | `statusDisplay.ts:85-95`；`StatusParser.kt:82-84` |
| L3 | 隧道协议不区分 IPv6，官方加 `6` 后缀。 | 检查 tunnel local_addr host 是否 IPv6，追加 `6`。 | `Status.vue:109-133`；`StatusParser.kt:79-81` |
| L4 | 版本空值显示空串；节点数不含本机。 | 空值显示 `unknown`；如需与官方一致，计数含本机行。 | `Status.vue:98-100,265-270`；`StatusParser.kt:106`、`PeersScreen.kt:113` |
| L5 | NAT 名称未完整映射。 | 使用官方 10 种 NAT 名称映射。 | `statusDisplay.ts:3-14`；`StatusParser.kt:113-124`、`Formatters.kt:45-51` |
| L6 | 速率单位/窗口不同：官方 KiB、2 秒；缇喵 KB、1 秒。 | 统一二进制单位和窗口。 | `Status.vue:68-86,272-292`；`Formatters.kt:7-15`、`StatusParser.kt:58-69` |
| L7 | 节点表不显示公网服务器/避免中继标签。 | 解析 `feature_flag.is_public_server`、`avoid_relay_data`，在主机名列显示标签。 | `Status.vue:257-263,426-442`；`Status.kt:5-17`、`StatusParser.kt:71-108` |
| L8 | 扫码导入仅占位。 | 接 ML Kit 或删除入口。 | `NetworkEditScreen.kt:123-127,375-384` |

不作为缺口：官方当前 GUI 源码没有公共服务器选择列表（`network.ts:99-100,345-375`；`NetworkList.vue:38-43,111-136`）。缇喵的服务器列表已覆盖 Manual 模式。

---

## 5. 证据索引

- 官方配置 UI：`O/easytier-web/frontend-lib/src/components/Config.vue`
- 官方配置类型/默认值：`O/easytier-web/frontend-lib/src/types/network.ts`
- 官方状态页：`O/easytier-web/frontend-lib/src/components/Status.vue`
- 官方状态口径：`O/easytier-web/frontend-lib/src/modules/statusDisplay.ts`
- 官方 VPN portal：`O/easytier-web/frontend-lib/src/modules/vpnPortal.ts`、`VpnPortalDialog.vue`
- 官方 Android VPN：`O/tauri-plugin-vpnservice/android/src/main/java/TauriVpnService.kt`
- 官方 Android 启动/同步：`O/easytier-gui/src/composables/mobile_vpn.ts`
- 官方移动 TUN 配置：`O/easytier/src/instance/factory/mobile_tun.rs`
- 官方 API 输入映射：`O/easytier-core/src/config/api_input.rs`
- 缇喵 core 配置：`T/core/src/main/java/app/timiao/et/core/NetworkConfig.kt`
- 缇喵 TOML 编解码：`T/core/src/main/java/app/timiao/et/core/TomlCodec.kt`
- 缇喵状态解析：`T/core/src/main/java/app/timiao/et/core/StatusParser.kt`
- 缇喵编辑界面：`T/app/src/main/java/app/timiao/et/ui/screens/NetworkEditScreen.kt`
- 缇喵 UI 模型：`T/app/src/main/java/app/timiao/et/ui/data/UiNetwork.kt`
- 缇喵 TUN：`T/app/src/main/java/app/timiao/et/service/TimiaoVpnService.kt`
- 缇喵引擎：`T/core/src/main/java/app/timiao/et/core/JniEngine.kt`
