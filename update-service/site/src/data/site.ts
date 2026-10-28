export const site = {
  name: 'DiPlay 凯美瑞 A 导航适配版',
  shortName: 'DiPlay 凯美瑞版',
  intro:
    '为八代凯美瑞先锋 A 导航安卓车机提供有线和无线 CarPlay。地图、音乐和通话直接出现在车机屏幕上，不需要越狱、转接盒、账号或认证服务器。',

  repoUrl: 'https://github.com/ppxpp/DiPlay',

  // 百度统计站点代码（hm.js 后的哈希）。会出现在每页源码中，属于公开值。
  analytics: {
    baiduTongjiId: '62693ab2210bbdb62e3bd3943799b414'
  },
  // 待填：与本 APK 对应的 tag 或 commit，GPL-3.0 要求分发安装包时能取到对应源码
  sourceRefUrl: '',

  // 待填：飞书文档索引页；为空时首页“查看全部文档”显示为待发布
  docs: {
    index: '',
    install: 'https://my.feishu.cn/wiki/DrNzwzq6miBWFUk6t1gcYSVwn3g',
    features: '',
    knownIssues: ''
  },
  // 待填：反馈渠道（群、邮箱或 issue 页面）
  feedbackUrl: '',

  device: {
    model: '八代凯美瑞 A 导航',
    verifiedFirmware: '152 版本',
    connection: 'Wi-Fi 热点 + 外接蓝牙 / USB'
  },

  scope:
    '目前只在 152 版本上完成验证，其他版本尚未验证。其他车型与车机不在支持范围内，也没有增加支持的计划；误装不会损坏系统，但功能无法保证。',


  limits: [
    '这是社区公开预览版，未经 Apple 认证。配件认证身份取自公开固件，属于实验性身份，其私钥可被提取，未来 iOS 版本是否继续接受尚未确定。',
    '本项目按“现状”提供，依 GPL-3.0 分发，无任何担保。',
    '使用可能影响行车安全，并可能导致原厂保修失效。驾驶员对安全驾驶负全部责任，请在停车状态下安装与测试，不要把本项目用于任何安全关键功能。',
    '部分场景仍可能出现画面卡顿、交互延迟或功能不生效。请先按安装教程调整分辨率、帧率与视频编码后再反馈。',
    'App 在同意启动声明后上报匿名使用统计（可在设置中关闭），不收集身份信息，也不存在认证服务器；CarPlay 数据只在你的 iPhone 与车机之间处理。网站使用百度统计记录页面访问量，不用于广告或用户画像。'
  ],

  disclaimers: [
    '本项目是独立的社区项目，与 Apple Inc.、丰田汽车、先锋电子及腾讯无隶属关系，也未获任何一方授权或认可。',
    '未经 Apple 认证，使用的配件认证身份为公开固件中提取的实验性身份，未来可能失效。',
    '在车机上运行第三方软件可能影响行车安全并导致原厂保修失效，驾驶员负全部责任，请在停车状态下安装与使用。',
    '软件依 GPL-3.0 按“现状”提供，无担保，作者不对任何损失承担责任；分发安装包的同时提供对应完整源码。',
    'App 不收集身份信息，无认证服务器；网站使用百度统计进行访问统计。下载与安装行为由你自主决定并承担后果。'
  ],

  footer:
    '基于上游 DiPlay（xcertplay）项目的适配版本。CarPlay、iPhone 及 CarPlay 图标均为 Apple Inc. 的商标或注册商标，仅作描述性用途。'
};

export type Site = typeof site;
