#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""静态检查 Kotlin 文件里「用到但没 import」的常见 Compose 符号。

## 为什么需要这个
CI 一轮要 5 分钟，而漏 import 这种错误一次只报一两个，
修一个推一次 = 一个下午就过去了。这个脚本在本地一次性扫全项目，
把这类错误在推送前全部找出来。

## 局限（已知的误报来源）
- `.alpha` / `.size` 这类**属性访问**会被误判成修饰符调用 ——
  所以判据用「前面不是 .」来排除成员访问，但 `Modifier.size(...)` 里的
  `Modifier.` 也会被排除，需要靠 CATALOG 里同时收录 `Modifier` 来兜住。
- 只覆盖 CATALOG 里列出的符号；新增 API 时记得往表里加。
"""
import re, sys, glob

CATALOG = {
    # foundation.layout
    'size': 'androidx.compose.foundation.layout.size',
    'heightIn': 'androidx.compose.foundation.layout.heightIn',
    'widthIn': 'androidx.compose.foundation.layout.widthIn',
    'fillMaxSize': 'androidx.compose.foundation.layout.fillMaxSize',
    'fillMaxWidth': 'androidx.compose.foundation.layout.fillMaxWidth',
    'fillMaxHeight': 'androidx.compose.foundation.layout.fillMaxHeight',
    'imePadding': 'androidx.compose.foundation.layout.imePadding',
    'navigationBarsPadding': 'androidx.compose.foundation.layout.navigationBarsPadding',
    'statusBarsPadding': 'androidx.compose.foundation.layout.statusBarsPadding',
    'PaddingValues': 'androidx.compose.foundation.layout.PaddingValues',
    'aspectRatio': 'androidx.compose.foundation.layout.aspectRatio',
    'Box': 'androidx.compose.foundation.layout.Box',
    'Column': 'androidx.compose.foundation.layout.Column',
    'Row': 'androidx.compose.foundation.layout.Row',
    'Spacer': 'androidx.compose.foundation.layout.Spacer',
    'Arrangement': 'androidx.compose.foundation.layout.Arrangement',
    # foundation 其它
    'clickable': 'androidx.compose.foundation.clickable',
    'background': 'androidx.compose.foundation.background',
    'border': 'androidx.compose.foundation.border',
    'verticalScroll': 'androidx.compose.foundation.verticalScroll',
    'rememberScrollState': 'androidx.compose.foundation.rememberScrollState',
    'LazyColumn': 'androidx.compose.foundation.lazy.LazyColumn',
    'LazyRow': 'androidx.compose.foundation.lazy.LazyRow',
    'itemsIndexed': 'androidx.compose.foundation.lazy.itemsIndexed',
    'RoundedCornerShape': 'androidx.compose.foundation.shape.RoundedCornerShape',
    'CircleShape': 'androidx.compose.foundation.shape.CircleShape',
    # material3
    'Icon': 'androidx.compose.material3.Icon',
    'IconButton': 'androidx.compose.material3.IconButton',
    'Text': 'androidx.compose.material3.Text',
    'Button': 'androidx.compose.material3.Button',
    'ButtonDefaults': 'androidx.compose.material3.ButtonDefaults',
    'TextButton': 'androidx.compose.material3.TextButton',
    'OutlinedButton': 'androidx.compose.material3.OutlinedButton',
    'OutlinedTextField': 'androidx.compose.material3.OutlinedTextField',
    'TextField': 'androidx.compose.material3.TextField',
    'TextFieldDefaults': 'androidx.compose.material3.TextFieldDefaults',
    'Surface': 'androidx.compose.material3.Surface',
    'Scaffold': 'androidx.compose.material3.Scaffold',
    'MaterialTheme': 'androidx.compose.material3.MaterialTheme',
    'AlertDialog': 'androidx.compose.material3.AlertDialog',
    'Switch': 'androidx.compose.material3.Switch',
    'Slider': 'androidx.compose.material3.Slider',
    'SliderDefaults': 'androidx.compose.material3.SliderDefaults',
    'Checkbox': 'androidx.compose.material3.Checkbox',
    'CircularProgressIndicator': 'androidx.compose.material3.CircularProgressIndicator',
    'HorizontalDivider': 'androidx.compose.material3.HorizontalDivider',
    'DropdownMenu': 'androidx.compose.material3.DropdownMenu',
    'DropdownMenuItem': 'androidx.compose.material3.DropdownMenuItem',
    'TopAppBar': 'androidx.compose.material3.TopAppBar',
    'TopAppBarDefaults': 'androidx.compose.material3.TopAppBarDefaults',
    'FloatingActionButton': 'androidx.compose.material3.FloatingActionButton',
    'Icons': 'androidx.compose.material.icons.Icons',
    # runtime
    'Composable': 'androidx.compose.runtime.Composable',
    'remember': 'androidx.compose.runtime.remember',
    'mutableStateOf': 'androidx.compose.runtime.mutableStateOf',
    'LaunchedEffect': 'androidx.compose.runtime.LaunchedEffect',
    'rememberCoroutineScope': 'androidx.compose.runtime.rememberCoroutineScope',
    'collectAsState': 'androidx.compose.runtime.collectAsState',
    'getValue': 'androidx.compose.runtime.getValue',
    'setValue': 'androidx.compose.runtime.setValue',
    # ui
    'Modifier': 'androidx.compose.ui.Modifier',
    'Alignment': 'androidx.compose.ui.Alignment',
    'Color': 'androidx.compose.ui.graphics.Color',
    'Brush': 'androidx.compose.ui.graphics.Brush',
    'clip': 'androidx.compose.ui.draw.clip',
    'alpha': 'androidx.compose.ui.draw.alpha',
    'dp': 'androidx.compose.ui.unit.dp',
    'sp': 'androidx.compose.ui.unit.sp',
    'FontWeight': 'androidx.compose.ui.text.font.FontWeight',
    'TextOverflow': 'androidx.compose.ui.text.style.TextOverflow',
    'TextAlign': 'androidx.compose.ui.text.style.TextAlign',
    'ContentScale': 'androidx.compose.ui.layout.ContentScale',
    'LocalContext': 'androidx.compose.ui.platform.LocalContext',
    'AsyncImage': 'coil.compose.AsyncImage',
    # 项目内
    'ChatClient': 'com.ruxi.app.data.api.ChatClient',
    'StreamEvent': 'com.ruxi.app.data.api.StreamEvent',
    'Effort': 'com.ruxi.app.data.model.Effort',
    'PromptEngine': 'com.ruxi.app.data.prompt.PromptEngine',
    'AppStore': 'com.ruxi.app.data.store.AppStore',
    'LocalAppearance': 'com.ruxi.app.ui.theme.LocalAppearance',
    'RuxiTextField': 'com.ruxi.app.ui.components.RuxiTextField',
    'SectionTitle': 'com.ruxi.app.ui.components.SectionTitle',
    'RuxiColors': 'com.ruxi.app.ui.theme.RuxiColors',
    'launch': 'kotlinx.coroutines.launch',
    'Job': 'kotlinx.coroutines.Job',
    'ListSerializer': 'kotlinx.serialization.builtins.ListSerializer',
    'serializer': 'kotlinx.serialization.builtins.serializer',
}

# 这些符号名太通用，做属性/变量名时误报率高，跳过
SKIP_IF_MEMBER = {'size', 'alpha', 'Text', 'Box', 'Row', 'Column', 'background', 'border',
                  'remember', 'serializer', 'items', 'itemsIndexed', 'Spacer', 'Icon',
                  'Button', 'Surface', 'Switch', 'Slider'}


def check(files):
    bad = 0
    for f in files:
        src = open(f, encoding='utf-8').read()
        lines = src.split('\n')
        imports = {l.strip() for l in lines if l.startswith('import')}

        # 本文件所在的包：同包内的符号**不需要 import**。
        # 不做这个判断的话，每个定义了自己类/函数的文件都会误报（如 ChatClient.kt 里
        # 用 ChatClient、Theme.kt 里用 RuxiColors），噪音会淹没真问题。
        pkg = ''
        for l in lines:
            if l.startswith('package '):
                pkg = l[len('package '):].strip()
                break
        code = '\n'.join(
            l for l in lines
            if not l.startswith('import')
            and not l.strip().startswith('*')
            and not l.strip().startswith('//')
            and not l.strip().startswith('/*')
        )
        miss = []
        for sym, path in CATALOG.items():
            if sym in SKIP_IF_MEMBER:
                # 只认「函数调用」形态：sym(  且前面不是 .
                pat = r'(?<![\w.])' + re.escape(sym) + r'\s*\('
            else:
                pat = r'(?<![\w.])' + re.escape(sym) + r'(?![\w])'
            if not re.search(pat, code):
                continue
            # 同包（或子包）里的符号无需 import，跳过
            if pkg and (path == pkg or path.startswith(pkg + '.')):
                continue
            if not any(i == f'import {path}' or i.startswith(f'import {path}.')
                       for i in imports):
                miss.append((sym, path))
        if miss:
            bad += 1
            print(f"\n⚠ {f.split('/')[-1]}")
            for sym, path in miss:
                print(f"    {sym:26s} -> import {path}")
    return bad


if __name__ == '__main__':
    targets = sys.argv[1:] or sorted(glob.glob('app/src/main/java/**/*.kt', recursive=True))
    n = check(targets)
    print("\n✅ 全部通过" if n == 0 else f"\n共 {n} 个文件有问题")
