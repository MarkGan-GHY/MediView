# 手机端服务自查文档

> **目标读者**：负责手机端 HTTP 服务开发的 AI/开发者  
> **问题现象**：眼镜端拍照后提示"连接超时（5s），手机服务端可能未启动"  
> **更新时间**：2026-04-01

---

## 1. 问题定位

眼镜端报错 `java.net.SocketTimeoutException`（连接超时），说明：
- ✅ 眼镜已连上手机热点（网络层通了）
- ✅ 眼镜能路由到手机 IP `192.168.43.1`
- ❌ TCP 握手在 5 秒内未完成 → **端口 8080 不可达**

---

## 2. 最可能的原因（按优先级）

### 原因 1：服务监听地址错误（90% 概率）

**错误示例：**

```python
# ❌ Flask/FastAPI 只监听 localhost
app.run(host='127.0.0.1', port=8080)
app.run(host='localhost', port=8080)
```

```javascript
// ❌ Express/Node.js 只监听 localhost
app.listen(8080, 'localhost')
app.listen(8080, '127.0.0.1')
```

```java
// ❌ Spring Boot 默认只监听 localhost
server.address=127.0.0.1
```

**为什么会超时？**  
`127.0.0.1` 只接受本机回环连接，眼镜从热点网络（`192.168.43.x`）访问时，手机系统直接丢弃 SYN 包，导致连接超时。

**正确写法：**

```python
# ✅ 监听所有网络接口（包括热点）
app.run(host='0.0.0.0', port=8080)
```

```javascript
// ✅ 监听所有接口
app.listen(8080, '0.0.0.0')
// 或省略 host 参数（默认 0.0.0.0）
app.listen(8080)
```

```java
// ✅ Spring Boot 监听所有接口
server.address=0.0.0.0
// 或删除该配置（默认 0.0.0.0）
```

---

### 原因 2：服务未启动或崩溃

**自查清单：**
- [ ] 服务进程是否在运行？（`ps aux | grep python/node/java`）
- [ ] 启动日志是否显示 `Listening on 0.0.0.0:8080` 或类似信息？
- [ ] 是否有未捕获的异常导致服务启动失败？

---

### 原因 3：端口被占用

如果 8080 已被其他应用占用，服务启动会失败。

**检查方法：**
```bash
# Android Termux 或 ADB shell
netstat -tuln | grep 8080
lsof -i :8080
```

**解决：**
- 杀掉占用进程，或
- 改用其他端口（同步修改眼镜端 `SERVER_HOST`）

---

### 原因 4：防火墙/安全软件拦截

部分手机安全软件（如小米安全中心、华为手机管家）会拦截非标准端口。

**解决：**
- 临时关闭"网络防护"功能测试
- 或在安全软件中将服务 App 加入白名单

---

## 3. 验证方法

### 步骤 1：确认端口对外开放

在手机终端（Termux/ADB shell）运行：
```bash
netstat -tuln | grep 8080
```

**期望输出：**
```
tcp  0.0.0.0:8080  0.0.0.0:*  LISTEN
```

**错误输出：**
```
tcp  127.0.0.1:8080  0.0.0.0:*  LISTEN  # ❌ 只监听本地
```

---

### 步骤 2：本机自测

在手机浏览器访问：
```
http://192.168.43.1:8080/analyzeDrug
```

- 如果能访问（即使返回 405 Method Not Allowed）→ 端口已开放
- 如果无法连接 → 服务未正确监听

---

### 步骤 3：查看服务启动日志

确认日志中包含类似信息：
```
✅ Running on http://0.0.0.0:8080
✅ Server listening on 0.0.0.0:8080
✅ Started server on 0.0.0.0:8080
```

而不是：
```
❌ Running on http://127.0.0.1:8080
❌ Server listening on localhost:8080
```

---

## 4. 眼镜端接口规范（供参考）

### 请求格式
```http
POST /analyzeDrug HTTP/1.1
Host: 192.168.43.1:8080
Content-Type: image/jpeg
Content-Length: <图片字节大小>

<JPEG 二进制数据>
```

**注意：**
- 请求体是 **raw binary**（不是 Base64，不是 multipart/form-data）
- 图片分辨率：1920×1080
- 典型大小：200KB - 500KB

### 响应格式（成功）
```json
{
  "success": true,
  "drugName": "阿莫西林胶囊",
  "confidence": 0.95,
  "warningText": "青霉素过敏者禁用",
  "needConfirm": false
}
```

### 响应格式（失败）
```json
{
  "success": false,
  "error": "识别失败：图片模糊"
}
```

**超时限制：**
- 连接超时：5 秒
- 读取超时：15 秒（从发送请求到收到完整响应）

---

## 5. 快速修复 Checklist

- [ ] 服务监听地址改为 `0.0.0.0:8080`
- [ ] 重启服务，确认启动日志显示 `0.0.0.0:8080`
- [ ] 手机浏览器访问 `http://192.168.43.1:8080/analyzeDrug` 能连通
- [ ] 关闭手机安全软件的网络防护（临时测试）
- [ ] 确认手机热点已开启且眼镜已连接

---

## 6. 常见框架配置示例

### Python Flask
```python
from flask import Flask, request, jsonify

app = Flask(__name__)

@app.route('/analyzeDrug', methods=['POST'])
def analyze():
    image_bytes = request.get_data()
    # ... 处理逻辑
    return jsonify({
        "success": True,
        "drugName": "示例药物",
        "confidence": 0.9,
        "warningText": "示例警告",
        "needConfirm": False
    })

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=8080, debug=False)
```

### Python FastAPI
```python
from fastapi import FastAPI, Request
import uvicorn

app = FastAPI()

@app.post('/analyzeDrug')
async def analyze(request: Request):
    image_bytes = await request.body()
    # ... 处理逻辑
    return {
        "success": True,
        "drugName": "示例药物",
        "confidence": 0.9,
        "warningText": "示例警告",
        "needConfirm": False
    }

if __name__ == '__main__':
    uvicorn.run(app, host='0.0.0.0', port=8080)
```

### Node.js Express
```javascript
const express = require('express');
const app = express();

app.use(express.raw({ type: 'image/jpeg', limit: '10mb' }));

app.post('/analyzeDrug', (req, res) => {
    const imageBuffer = req.body;
    // ... 处理逻辑
    res.json({
        success: true,
        drugName: '示例药物',
        confidence: 0.9,
        warningText: '示例警告',
        needConfirm: false
    });
});

app.listen(8080, '0.0.0.0', () => {
    console.log('Server listening on 0.0.0.0:8080');
});
```

---

## 7. 如果问题仍未解决

1. 在手机上抓包（tcpdump/Packet Capture）查看是否收到眼镜的 SYN 包
2. 检查手机是否开启了"仅允许特定设备连接热点"限制
3. 尝试更换端口（如 8000、5000），同步修改眼镜端配置
4. 确认 Android 系统版本是否有特殊网络限制（Android 12+ 部分机型）
