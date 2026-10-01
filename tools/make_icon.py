#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成「如戏」应用图标（v2）。

## v1 的问题（实际渲染后才发现）
- 面具轮廓做成了圆角方形 → 看起来像白色药丸/幽灵，不像面具
- 顶部高光用椭圆+高斯模糊 → 变成一块突兀的白斑，像头顶破了个洞
- 眼睛是正圆实心 → 是"卡通脸"的语法，不是"面具"的语法

## v2 的关键修正
1. **眼部镂空**：眼睛挖成透明洞，透出背景深紫。
   这是"面具"与"脸"的分水岭 —— 实心眼读作脸，镂空眼读作面具。
2. **蛋形轮廓**：顶部窄、颧骨处最宽、下巴收尖。
   控制点全部重算，去掉"方"的感觉。
3. **去掉高光斑**，改用面具自身的柔和内渐变。
4. 眼型改为**斜向杏仁形**，带一点点上挑，更有戏感。
5. 唇线用镂空细弧，与眼部语言统一。

输出：各密度 mipmap（含自适应图标两层）。
"""

import os
from PIL import Image, ImageDraw, ImageFilter

S = 1024
K = S / 108.0


def P(x, y):
    return (x * K, y * K)


def cubic(p0, p1, p2, p3, n=120):
    pts = []
    for i in range(n + 1):
        t = i / n
        mt = 1 - t
        x = mt**3 * p0[0] + 3 * mt * mt * t * p1[0] + 3 * mt * t * t * p2[0] + t**3 * p3[0]
        y = mt**3 * p0[1] + 3 * mt * mt * t * p1[1] + 3 * mt * t * t * p2[1] + t**3 * p3[1]
        pts.append((x, y))
    return pts


def make_gradient(size, c1, c2):
    """对角渐变。用 2x2 拉伸实现，比逐像素快几十倍且视觉无差别。"""
    small = Image.new("RGB", (2, 2))
    small.putpixel((0, 0), c1)
    small.putpixel((1, 0), tuple((a + b) // 2 for a, b in zip(c1, c2)))
    small.putpixel((0, 1), tuple((a + b) // 2 for a, b in zip(c1, c2)))
    small.putpixel((1, 1), c2)
    return small.resize((size, size), Image.BICUBIC)


def mask_shape(scale=1.0):
    """面具本体（不含五官），返回 RGBA。"""
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # 蛋形：顶部收窄(54,20) → 颧骨最宽(24/84, 54) → 下巴收尖(54,86)
    # 用四段三次贝塞尔，控制点让两侧饱满、下巴尖而不锐
    outline = []
    outline += cubic((54, 20), (36, 20), (25, 34), (24, 50))     # 左上
    outline += cubic((24, 50), (23, 68), (36, 86), (54, 86))     # 左下（收下巴）
    outline += cubic((54, 86), (72, 86), (85, 68), (84, 50))     # 右下
    outline += cubic((84, 50), (83, 34), (72, 20), (54, 20))     # 右上

    pts = [P(x, y) for (x, y) in outline]

    # 外发光：让面具从深色背景里"浮"起来
    glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(glow).polygon(pts, fill=(168, 140, 255, 110))
    glow = glow.filter(ImageFilter.GaussianBlur(30))
    img = Image.alpha_composite(img, glow)

    d = ImageDraw.Draw(img)
    d.polygon(pts, fill=(246, 240, 255, 255))

    if scale < 1.0:
        small = img.resize((int(S * scale), int(S * scale)), Image.LANCZOS)
        canvas = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        off = (S - small.size[0]) // 2
        canvas.paste(small, (off, off), small)
        return canvas
    return img


def almond(cx, cy, w, h, tilt_deg=0.0, sharp=0.22):
    """杏仁眼（返回折线点，单位制）。

    ## v2/v3 踩的坑
    原来 sharp=0.35 + tilt=±12°，渲染出来是**斜向的尖菱形**，
    读作「生气的眉毛」而不是眼睛 —— 两只眼内高外低，是愤怒表情的语法。
    修正：tilt 归零（水平）、sharp 减到 0.22（两端只是微微收尖）。
    眼睛要"饱满"才像眼睛，太尖就变成符号。
    """
    import math
    pts = []
    n = 90
    rad = math.radians(tilt_deg)
    for i in range(n + 1):
        a = 2 * math.pi * i / n
        ex = math.cos(a) * (w / 2)
        ey = math.sin(a) * (h / 2)
        # 两端轻微收尖（0 = 纯椭圆，1 = 尖菱形）
        ey *= 1.0 - sharp * abs(math.cos(a))
        rx = ex * math.cos(rad) - ey * math.sin(rad)
        ry = ex * math.sin(rad) + ey * math.cos(rad)
        pts.append((cx + rx, cy + ry))
    return pts


def thick_curve(pts, width, taper=1.0):
    """把一条曲线变成**填充多边形**（上下偏移合成闭合区域）。

    ## 为什么不能用 ImageDraw.line 的 width
    line 的宽边是逐段矩形拼接，弯曲处会露出接缝 —— 渲染出来是锯齿状，
    像一排牙齿。镂空时特别明显（v2 就踩了这个坑）。
    填充多边形则是一条光滑的带子，边缘连续。

    [taper] < 1 时两端变细，做出"笔锋"。
    """
    import math
    n = len(pts)
    if n < 2:
        return pts

    top, bot = [], []
    for i, (x, y) in enumerate(pts):
        # 用相邻点估计切线方向
        if i == 0:
            dx, dy = pts[1][0] - pts[0][0], pts[1][1] - pts[0][1]
        elif i == n - 1:
            dx, dy = pts[-1][0] - pts[-2][0], pts[-1][1] - pts[-2][1]
        else:
            dx, dy = pts[i + 1][0] - pts[i - 1][0], pts[i + 1][1] - pts[i - 1][1]
        ln = math.hypot(dx, dy) or 1.0
        # 法线
        nx, ny = -dy / ln, dx / ln

        # 两端渐细：t 从 0 到 1，中间最粗
        t = i / (n - 1)
        edge = min(t, 1 - t) * 2            # 0(端) -> 1(中)
        w = width * (1.0 - taper) + width * taper * (edge ** 0.45)

        top.append((x + nx * w / 2, y + ny * w / 2))
        bot.append((x - nx * w / 2, y - ny * w / 2))

    return top + bot[::-1]


def render(scale=1.0):
    """完整图标内容（透明底，含五官镂空）。"""
    body = mask_shape(scale=scale)

    from PIL import ImageChops

    holes = Image.new("L", (S, S), 0)
    hd = ImageDraw.Draw(holes)

    # 眼睛：**水平**杏仁，饱满为主。
    # v3 的斜向尖眼读作"怒眉"；v4 收尖 0.22 仍略凶 —— 降到 0.10 更圆润温和。
    for (cx, cy) in ((41.0, 44.5), (67.0, 44.5)):
        pts = [P(x, y) for (x, y) in almond(cx, cy, 15.0, 11.0, tilt_deg=0.0, sharp=0.10)]
        hd.polygon(pts, fill=255)

    # 唇：用填充带子（thick_curve）而不是 line —— line 的弯折处有接缝，
    # 渲染出来是锯齿，像一排牙齿。两端收细做出自然的"嘴角"。
    mouth = cubic((43.5, 63.0), (49, 73.0), (59, 73.0), (64.5, 63.0), n=80)
    mouth_px = [P(x, y) for (x, y) in mouth]
    hd.polygon(thick_curve(mouth_px, width=2.9 * K, taper=0.55), fill=255)

    # 鼻子：v3 那条又小又淡，像块污渍。索性去掉 ——
    # 少一个元素，小尺寸下反而更干净（48px 时鼻子只有 1px，纯噪音）。

    body.putalpha(ImageChops.subtract(body.split()[3], holes))
    return body


def rounded_mask(size, radius_ratio):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).rounded_rectangle(
        [0, 0, size - 1, size - 1], radius=int(size * radius_ratio), fill=255
    )
    return m


def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, "PNG", optimize=True)


def main():
    root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                        "app", "src", "main", "res")

    grad = make_gradient(S, (22, 13, 45), (78, 44, 112))       # #160D2D -> #4E2C70
    art = render(scale=1.0)
    art_safe = render(scale=0.60)                               # 自适应前景安全区

    full = Image.alpha_composite(grad.convert("RGBA"), art)

    full_square = full.copy()
    full_square.putalpha(rounded_mask(S, 0.23))

    full_round = full.copy()
    full_round.putalpha(rounded_mask(S, 0.5))

    sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    fg_sizes = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

    for dpi, sz in sizes.items():
        base = os.path.join(root, f"mipmap-{dpi}")
        save(full_square.resize((sz, sz), Image.LANCZOS), os.path.join(base, "ic_launcher.png"))
        save(full_round.resize((sz, sz), Image.LANCZOS), os.path.join(base, "ic_launcher_round.png"))

    for dpi, sz in fg_sizes.items():
        base = os.path.join(root, f"mipmap-{dpi}")
        save(art_safe.resize((sz, sz), Image.LANCZOS),
             os.path.join(base, "ic_launcher_foreground.png"))
        save(grad.convert("RGBA").resize((sz, sz), Image.LANCZOS),
             os.path.join(base, "ic_launcher_background.png"))

    # 预览：大图 + 桌面实际尺寸对比
    pad = 28
    big = 420
    small = 96
    canvas = Image.new("RGB", (big + small * 3 + pad * 4, big + pad * 2), (14, 10, 24))
    canvas.paste(full_square.resize((big, big), Image.LANCZOS), (pad, pad), None)
    x = big + pad * 2
    for s in (small, 72, 48):
        im = full_square.resize((s, s), Image.LANCZOS)
        canvas.paste(im, (x, pad + (big - s) // 2), im)
        x += s + pad
    prev = os.path.join(os.path.dirname(os.path.abspath(__file__)), "icon_preview.png")
    canvas.save(prev)
    print("预览:", prev)


if __name__ == "__main__":
    main()
