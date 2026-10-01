# XTCRotateFix

XTC **w5100** 平台手表（monaco_go，Android 11）**屏幕旋转解锁**项目。

> **支持设备**：理论上支持所有 XTC **w5100** 平台设备；其中 **Z10、Z11 已实机测试通过**。
> 其余 w5100 机型通常可直接使用；若某 ROM 把 `DisplayRotation` 的字段/方法改了名，
> 按 [`lspmod/README.md`](lspmod/README.md) 的符号对照表微调即可。
> 实机测试版本：`XTC/ND08/monaco_go:11/RKQ1.220916.001`（DXY_2.7.5.26.8.7）。

厂商在 Wear 形态的 framework 层把屏幕旋转彻底关死（配置 + 代码双重阉割）。本项目用一个
LSPosed 模块在运行时解除封锁，**不修改任何 ROM 文件**。

## 目录

| 路径 | 说明 |
|------|------|
| [`lspmod/`](lspmod/) | **XgjRotateFix** LSPosed 模块：源码、构建脚本与签名好的 `XTCRotateFix.apk` |
| [`lspmod/README.md`](lspmod/README.md) | 模块说明：根因（实测修正版）、各 hook 做了什么、安装/使用、实测结果 |
| [`旋转失效-排查与修复方案.md`](旋转失效-排查与修复方案.md) | 纯静态分析排查报告：根因链、证据链、改 ROM / RRO overlay 的修复方案 |

## 快速开始

```bash
adb install -r lspmod/XTCRotateFix.apk
```

在 LSPosed 管理器中启用「旋转修复」，作用域勾选 **系统框架 (android)**，重启。
详见 [`lspmod/README.md`](lspmod/README.md)。

## 构建

```bash
cd lspmod && bash build.sh      # 产物 ./XTCRotateFix.apk
```

依赖 Android SDK build-tools 与一份代码签名证书（LSPosed 模块不要求平台签名，用自己的证书即可）。
本仓库**不包含任何证书、密钥与密码**，`build.sh` 中的证书路径请替换为自己的。

## 适用范围与免责声明

- 仅针对上述特定 ROM 版本；换 ROM 版本需按 `lspmod/README.md` 的符号对照表核对。
- 刷机 / Xposed 有风险，请自行评估。本项目仅供学习与研究使用。

## 许可

本项目以 [MIT License](LICENSE) 开源。
