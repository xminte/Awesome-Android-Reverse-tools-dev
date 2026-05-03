import frida
import sys
from termcolor import colored


def to_string(byte_array):
    """
    将字节数组转换为字符串（已修复：支持Java有符号byte）
    """
    try:
        # 🔥 关键修复：把 -128~127 转成 0~255，彻底解决报错
        fixed_array = [b & 0xFF for b in byte_array]
        data = bytes(fixed_array)
        
        # 2. 解码为字符串
        # 通常使用 'utf-8'，如果是中文乱码可以尝试 'gbk'
        return data.decode('utf-8', errors='ignore')
    except:
        return ""  # 任何错误都直接返回空，不崩溃


def custom_hexdump(data, offset=0, length=None):
    """
    自定义 hexdump 函数，复刻标准 hexdump 输出格式
    :param data: 字节数据 (bytes 或 list)
    :param offset: 起始偏移量
    :param length: 读取长度 (None 表示全部)
    """
    try:
        # 1. 数据预处理
        if isinstance(data, list):
            data = [x & 0xFF for x in data]
            data = bytes(data)
        
        if length is None:
            length = len(data) - offset
        
        # 确保不越界
        data_slice = data[offset : offset + length]
        
        # 2. 逐行打印 (每行 16 字节)
        line_size = 16
        current_offset = offset
        
        for i in range(0, len(data_slice), line_size):
            chunk = data_slice[i : i + line_size]
            
            # --- A. 打印地址部分 (例如 00000000:) ---
            # 格式化为 8 位十六进制地址
            print(f"{current_offset:08x}: ", end="")
            
            # --- B. 打印十六进制部分 ---
            hex_part = ""
            ascii_part = ""
            
            for j, byte_val in enumerate(chunk):
                # 将整数转为十六进制字符串 (例如 72 -> '48')
                hex_val = f"{byte_val:02x}"
                hex_part += hex_val + " "
                
                # 构建右侧 ASCII 部分
                if 32 <= byte_val <= 126: # 可打印字符范围
                    ascii_part += chr(byte_val)
                else: # 不可打印字符用 . 代替
                    ascii_part += "."
            
            # 补齐空格，确保 ASCII 部分对齐
            # 如果这一行不足 16 字节，需要在十六进制部分补空格
            if len(chunk) < line_size:
                hex_part += "   " * (line_size - len(chunk))
                
            # 为了美观，在中间（第8个字节后）加两个空格
            hex_str = f"{hex_part[:24]} {hex_part[24:]}"
            
            print(f"{hex_str}  {ascii_part}")
            
            current_offset += len(chunk)
    except:
        pass


def smart_hexdump(data, truncate_zeros=True):
    """
    智能 Hexdump：支持自动截断末尾大量的 0
    truncate_zeros 为 true, 开启截断；为false，不截断；
    """
    try:
        # --- 新增：空数据检查 ---
        if not data or len(data) == 0:
            print("⚠️ 数据为空，跳过打印")
            return
        # ----------------------

        if isinstance(data, list):
            data = [x & 0xFF for x in data]
            data = bytes(data)
        
        # --- 核心逻辑：处理末尾大量的 0 ---
        display_data = data
        truncated_count = 0
        
        if truncate_zeros and len(data) > 0:
            # 从后往前找，看有多少个连续的 0
            i = len(data) - 1
            while i >= 0 and data[i] == 0:
                i -= 1
            
            # 计算末尾 0 的个数
            zeros_at_end = len(data) - 1 - i
            
            # 如果末尾 0 很多（比如超过 16 个），我们就进行截断显示
            if zeros_at_end > 16:
                truncated_count = zeros_at_end
                # 只保留前面的数据（保留到最后一个非0字节）
                display_data = data[:len(data) - truncated_count]

        # --- 标准 Hexdump 打印逻辑 ---
        line_size = 16
        current_offset = 0
        
        # 1. 打印主体数据
        for i in range(0, len(display_data), line_size):
            chunk = display_data[i : i + line_size]
            
            # 地址
            print(f"{current_offset:08x}: ", end="")
            
            # 十六进制部分
            hex_part = ""
            ascii_part = ""
            
            for byte_val in chunk:
                hex_part += f"{byte_val:02x} "
                ascii_part += chr(byte_val) if 32 <= byte_val <= 126 else "."
            
            # 补齐空格
            if len(chunk) < line_size:
                hex_part += "   " * (line_size - len(chunk))
            
            # 中间加空格美化
            hex_str = f"{hex_part[:24]} {hex_part[24:]}"
            print(f"{hex_str}  {ascii_part}")
            
            current_offset += len(chunk)

        # 2. 如果有截断，打印提示信息
        if truncated_count > 0:
            print(f"{'*':<8}  (省略了 {truncated_count} 个字节的 0)")
    except:
        pass


# --------------------------
# 🔥 输出到文件函数（新增）
# --------------------------
def write_to_file(content):
    try:
        with open("network_log.txt", "a", encoding="utf-8") as f:
            f.write(content + "\n")
    except:
        pass

# --- Frida 消息接收回调 ---
def on_message(message, data):
    try:
        if message['type'] == 'send':
            payload_bytes  = message['payload']
            
            # 🔥 增加数据校验，空数据直接跳过
            if not payload_bytes or not isinstance(payload_bytes, list):
                return
            
            try:
                # 控制台打印
                smart_hexdump(payload_bytes)
                result = to_string(payload_bytes)
                
                if result.strip():
                    print(colored(result, "blue"))
                    # 🔥 同时写入文件
                    write_to_file(result)
                
                # 分隔符
                write_to_file("-" * 60)
                
            except:
                pass  # 解析失败直接忽略，不报错
                
        elif message['type'] == 'error':
            print(f"❌ 脚本报错: {message['stack']}")
    except:
        pass  # 最外层兜底，永不报错

# --- 启动服务 ---
print("[*] 电脑端服务已启动，等待手机端连接...")
print("[*] 日志将自动保存到: network_log.txt")
device = frida.get_usb_device()
session = device.attach("com.whwy.equchong") # 替换为你的目标包名   com.ilulutv.fulao2

with open("Roysue_socket_send.js", "r", encoding="utf-8") as f:
    js_code = f.read()

script = session.create_script(js_code)
script.on('message', on_message)
script.load()

# 保持服务运行
sys.stdin.read()