# 项目简介
1. 本项目旨在提供一套实用的工具集，帮助安全研究人员和开发者更高效地进行逆向工程、调试和安全分析工作。
2. 目前包含以下功能模块：
   - 批量Demangle处理脚本：用于将C++函数名从混淆状态转换为可读状态，方便分析和调试。
   - Hook指令处理工具集：提供一系列工具，用于生成Hook指令、去重等操作，简化Hook过程。
   - RPC + Socket自吐 + 在电脑端输出抓包内容：实现RPC通信和Socket自吐功能，并在电脑端输出抓包内容，便于实时监控和分析。

<br><br>

# 目录

- [1. 批量Demangle处理脚本](#demangle)
- [2. Hook指令处理工具集](#hook-tools)
- [3. RPC + Socket自吐 + 在电脑端输出抓包内容](#rpc-socket)


<br><br>

<a id="demangle"></a>
# 1. 批量Demangle处理脚本

使用方法：

    demangle文件夹下，

    python demangle_exports.py exports.json


<br>
exports.json是  objection将so文件的导出函数列表导出为json格式后的文件，命令如下：

```
memory list exports libexample.so --json exports.json
```


<br>

效果如下：


![image-20260403115637013](png/demangle.png)


<br><br>

<a id="hook-tools"></a>
# 2. Hook指令处理工具集(去重、Hook指令生成等)

使用方法：

    hook_tools文件夹下，

    打开android_hook_tool.html文件，即可使用    

<br>


#### Hook类指令(objection)
![alt text](png/hook_tool1.png)

<br>

#### 去重
![alt text](png/hook_tool2.png)

<br><br>

<a id="rpc-socket"></a>
# 3. RPC +Socket自吐 + 在电脑端输出抓包内容



使用方法：

    socket 文件夹下，运行
    python hook_receive_socket.py
    Roysue_socket_send.js和hook_receive_socket.py配合使用

<br>
效果如下：

![alt text](png/socket.png)


<br>
PS：工具后续会继续完善和增加功能，敬请期待！欢迎大家提出意见和建议！