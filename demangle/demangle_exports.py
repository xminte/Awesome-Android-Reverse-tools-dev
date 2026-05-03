#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
批量Demangle处理脚本
用于处理exports.json文件中的C++ mangled符号
"""

import json
import argparse
import subprocess
import sys
import os
from pathlib import Path
from typing import List, Dict, Any, Optional

def demangle_cpp_symbol(mangled_name: str) -> Optional[str]:
    """
    使用c++filt工具demangle C++符号
    
    Args:
        mangled_name: mangled的C++符号名
        
    Returns:
        demangled后的符号名，如果失败返回None
    """
    try:
        # 使用c++filt进行demangle
        result = subprocess.run(
            ['c++filt'],
            input=mangled_name,
            text=True,
            capture_output=True,
            timeout=10
        )
        
        if result.returncode == 0:
            demangled = result.stdout.strip()
            # 如果demangled结果和原始相同，可能demangle失败
            if demangled != mangled_name:
                return demangled
            else:
                return None
        else:
            return None
            
    except (subprocess.TimeoutExpired, subprocess.SubprocessError, FileNotFoundError):
        return None

def demangle_with_python_fallback(mangled_name: str) -> Optional[str]:
    """
    使用Python备选方案进行demangle（如果c++filt不可用）
    """
    try:
        # 简单的demangle尝试（基础模式匹配）
        if mangled_name.startswith('_Z'):
            # 这是一个mangled C++符号
            # 这里可以添加一些简单的模式匹配，但完整的demangle需要c++filt
            pass
        
        return None
    except Exception:
        return None

def is_mangled_cpp_symbol(name: str) -> bool:
    """
    判断一个名称是否是mangled的C++符号
    """
    # C++ mangled符号通常以_Z开头
    return name.startswith('_Z')

def process_json_file(input_file: str, output_file: Optional[str] = None, 
                     force: bool = False, use_fallback: bool = False) -> Dict[str, Any]:
    """
    处理JSON文件，demangle所有的name字段
    
    Args:
        input_file: 输入JSON文件路径
        output_file: 输出文件路径，如果为None则不保存
        force: 是否强制重新demangle已存在的demangled_name字段
        use_fallback: 是否使用Python备选方案当c++filt不可用时
        
    Returns:
        处理统计信息
    """
    stats = {
        'total_entries': 0,
        'mangled_symbols': 0,
        'successfully_demangled': 0,
        'failed_demangle': 0,
        'skipped_demangle': 0
    }
    
    try:
        # 读取JSON文件
        with open(input_file, 'r', encoding='utf-8') as f:
            data = json.load(f)
        
        if not isinstance(data, list):
            print(f"错误: JSON文件应该包含一个数组")
            return stats
        
        stats['total_entries'] = len(data)
        
        # 处理每个条目
        for item in data:
            if not isinstance(item, dict) or 'name' not in item:
                continue
                
            name = item['name']
            
            # 检查是否是mangled符号
            if is_mangled_cpp_symbol(name):
                stats['mangled_symbols'] += 1
                
                # 检查是否已经存在demangled结果且不需要强制重新处理
                if not force and 'demangled_name' in item:
                    stats['skipped_demangle'] += 1
                    continue
                
                # 尝试demangle
                demangled = demangle_cpp_symbol(name)
                
                # 如果c++filt失败且允许使用备选方案
                if demangled is None and use_fallback:
                    demangled = demangle_with_python_fallback(name)
                
                if demangled is not None:
                    item['demangled_name'] = demangled
                    stats['successfully_demangled'] += 1
                    print(f"✓ Demangled: {name} -> {demangled}")
                else:
                    stats['failed_demangle'] += 1
                    print(f"✗ Failed to demangle: {name}")
        
        # 保存结果
        if output_file:
            with open(output_file, 'w', encoding='utf-8') as f:
                json.dump(data, f, indent=2, ensure_ascii=False)
            print(f"\n结果已保存到: {output_file}")
        
        return stats
        
    except Exception as e:
        print(f"处理文件时出错: {e}")
        return stats

def check_cppfilt_available() -> bool:
    """检查c++filt工具是否可用"""
    try:
        result = subprocess.run(['c++filt', '--version'], 
                              capture_output=True, text=True, timeout=5)
        return result.returncode == 0
    except (FileNotFoundError, subprocess.SubprocessError):
        return False

def main():
    parser = argparse.ArgumentParser(description='批量Demangle处理脚本')
    parser.add_argument('input', help='输入JSON文件路径')
    parser.add_argument('-o', '--output', help='输出文件路径（默认：原文件名_demangled.json）')
    parser.add_argument('-f', '--force', action='store_true', help='强制重新demangle所有符号')
    parser.add_argument('--fallback', action='store_true', help='当c++filt不可用时使用Python备选方案')
    parser.add_argument('--check-only', action='store_true', help='只检查不实际处理')
    
    args = parser.parse_args()
    
    # 检查输入文件是否存在
    if not os.path.exists(args.input):
        print(f"错误: 输入文件不存在: {args.input}")
        sys.exit(1)
    
    # 检查c++filt是否可用
    cppfilt_available = check_cppfilt_available()
    
    if not cppfilt_available:
        print("警告: c++filt工具未找到或不可用")
        if not args.fallback:
            print("请安装c++filt或使用--fallback选项使用备选方案")
            sys.exit(1)
        else:
            print("将使用Python备选方案（功能有限）")
    else:
        print("✓ c++filt工具可用")
    
    # 确定输出文件路径
    if args.output is None:
        input_path = Path(args.input)
        args.output = input_path.parent / f"{input_path.stem}_demangled{input_path.suffix}"
    
    if args.check_only:
        print(f"检查模式: 不会修改文件")
        print(f"输入文件: {args.input}")
        print(f"输出文件: {args.output}")
        print(f"c++filt可用: {cppfilt_available}")
        return
    
    print(f"开始处理文件: {args.input}")
    
    # 处理文件
    stats = process_json_file(
        args.input, 
        args.output, 
        args.force, 
        args.fallback
    )
    
    # 输出统计信息
    print(f"\n=== 处理统计 ===")
    print(f"总条目数: {stats['total_entries']}")
    print(f"mangled符号数: {stats['mangled_symbols']}")
    print(f"成功demangled: {stats['successfully_demangled']}")
    print(f"demangle失败: {stats['failed_demangle']}")
    print(f"跳过demangle: {stats['skipped_demangle']}")
    
    if stats['successfully_demangled'] > 0:
        print(f"\n✓ 处理完成!")
    else:
        print(f"\n⚠ 没有成功demangle任何符号")

if __name__ == "__main__":
    main()