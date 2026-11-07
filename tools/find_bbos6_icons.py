#!/usr/bin/env python3
"""Locate the exact numbered BBOS6 icon PNGs seen in user screenshots.

Temporary investigation utility. The six small reference crops were obtained
from the screenshots the user supplied (in order: Songs, Artists, Albums,
Genres, Playlists, Shuffle). No stock imagery or guessed icon IDs are used.
"""
import base64
import io
from pathlib import Path
import sys

import cv2
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ICON_DIR = ROOT / "assets/bbos6/icons"
TARGET_DATA = ROOT / "tools/icon-match-targets.b64"
LABELS = ["songs", "artists", "albums", "genres", "playlists", "shuffle"]
SIZE = 64
MARGIN = 5
ART_SIZE = SIZE - MARGIN * 2

def normalize(rgb, alpha, threshold=12):
    """Fit the colored foreground in a fixed canvas without aspect stretching."""
    fg = (alpha > threshold).astype(np.uint8)
    ys, xs = np.where(fg)
    if len(ys) < 6:
        return None
    x0,x1 = xs.min(),xs.max()+1
    y0,y1 = ys.min(),ys.max()+1
    patch = rgb[y0:y1, x0:x1].astype(np.float32)
    opacity = alpha[y0:y1, x0:x1].astype(np.float32)/255.0
    w,h = x1-x0,y1-y0
    scale = ART_SIZE/max(h,w)
    dw,dh = max(1,round(w*scale)),max(1,round(h*scale))
    patch = cv2.resize(patch,(dw,dh),interpolation=cv2.INTER_AREA if scale<1 else cv2.INTER_LINEAR)
    opacity = cv2.resize(opacity,(dw,dh),interpolation=cv2.INTER_AREA if scale<1 else cv2.INTER_LINEAR)
    if opacity.ndim==3: opacity=opacity[:,:,0]
    patch *= opacity[:,:,None]
    out = np.zeros((SIZE,SIZE,3), dtype=np.float32)
    ix=(SIZE-dw)//2; iy=(SIZE-dh)//2
    out[iy:iy+dh,ix:ix+dw]=patch
    return cv2.GaussianBlur(out,(3,3),0.55)

def load_ref():
    image=Image.open(io.BytesIO(base64.b64decode(TARGET_DATA.read_text().strip()))).convert("RGB")
    refs=[]
    for i in range(6):
        # Crop includes ~10 px of background on each side. Selected row
        # references have blue-gradient background; subtract that row color.
        rgb=np.asarray(image.crop((0,i*80,64,(i+1)*80))).astype(np.float32)
        bg=np.median(rgb[:,0:4,:],axis=1)
        diff=np.max(np.abs(rgb-bg[:,None,:]),axis=2)
        mask=(diff>17).astype(np.uint8)
        # Keep the color originally drawn on the icon, but mask everything
        # consistent with the row's local black/blue background.
        alpha=np.where(mask!=0,255,0).astype(np.uint8)
        normalized=normalize(rgb,alpha)
        if normalized is None: raise RuntimeError("Empty reference "+LABELS[i])
        refs.append(normalized)
        print("REFERENCE",LABELS[i],"foreground pixels",int(mask.sum()),flush=True)
    return refs

def load_icon(path):
    try:
        with Image.open(path) as im:
            if im.width<22 or im.height<22 or im.width>384 or im.height>384:
                return None
            rgba=np.asarray(im.convert("RGBA"))
    except Exception:
        return None
    # Transparent icons are composited over black, as in the screenshots.
    alpha=rgba[:,:,3]
    if alpha.max()<32: return None
    rgb=rgba[:,:,:3].astype(np.float32)
    # Purely black opaque bitmap backgrounds are treated like transparency;
    # this also avoids matching only large stretches of black.
    if np.mean(alpha>240)>0.95 and np.median(rgb)<12:
        nonblack = np.max(rgb,axis=2)>12
        alpha=np.where(nonblack,255,0).astype(np.uint8)
    return normalize(rgb,alpha)

def score(ref, image):
    # Both foregrounds have been cropped, centered and scaled identically.
    # Allow for a few pixels of screenshot scaling/placement error.
    best=1e9
    ref_edges=cv2.Canny(np.uint8(np.clip(ref,0,255)),50,130)
    for dy in (-3,0,3):
        for dx in (-3,0,3):
            shifted=np.roll(np.roll(image,dy,axis=0),dx,axis=1)
            color=np.mean(np.abs(ref-shifted))
            edges=cv2.Canny(np.uint8(np.clip(shifted,0,255)),50,130)
            edge=np.mean(np.abs(ref_edges.astype(np.float32)-edges.astype(np.float32)))
            # RGB similarity is strongly diagnostic for blue notes and
            # green shuffle icons, shape edges matter for gray icons.
            val=color+edge*0.13
            if val<best:best=val
    return best

def green_mask_normalized(arr):
    """Binary footprint of green arrows, ignoring the blue selection panel."""
    rgb=np.asarray(arr.convert("RGBA"))
    R,G,B=[rgb[:,:,i].astype(np.int16) for i in range(3)]
    a=rgb[:,:,3]
    green=((G>R+20)&(G>B+1)&(G>60)&(a>32)).astype(np.uint8)
    yy,xx=np.where(green>0)
    if len(xx)<8:return None
    patch=green[yy.min():yy.max()+1,xx.min():xx.max()+1]
    h,w=patch.shape
    scale=54/max(h,w)
    nw=max(1,round(w*scale));nh=max(1,round(h*scale))
    fitted=cv2.resize(patch,(nw,nh),interpolation=cv2.INTER_NEAREST)
    out=np.zeros((64,64),dtype=np.uint8)
    x=(64-nw)//2;y=(64-nh)//2
    out[y:y+nh,x:x+nw]=fitted
    return out

def green_rank():
    target=Image.open(io.BytesIO(base64.b64decode(TARGET_DATA.read_text().strip()))).convert("RGB")
    target=target.crop((0,5*80,64,6*80))
    ref=green_mask_normalized(target)
    if ref is None:raise RuntimeError("No green target pixels")
    scored=[]
    for icon in ICON_DIR.glob("*.png"):
        try:
            with Image.open(icon) as im:
                if im.width<22 or im.height<22 or im.width>384 or im.height>384:continue
                mask=green_mask_normalized(im)
        except Exception:continue
        if mask is None:continue
        best=0
        for dx in (-4,-2,0,2,4):
            for dy in (-4,-2,0,2,4):
                shifted=np.roll(np.roll(mask,dy,axis=0),dx,axis=1)
                overlap=np.count_nonzero((ref>0)&(shifted>0))
                union=np.count_nonzero((ref>0)|(shifted>0))
                iou=overlap/max(1,union)
                if iou>best:best=iou
        scored.append((best,icon.name))
    scored.sort(reverse=True)
    print("GREEN_MATCHES", " | ".join(f"{name}:{match:.4f}" for match,name in scored[:60]),flush=True)

def main():
    green_rank()
    refs=load_ref()
    results=[[] for _ in refs]
    candidates=list(ICON_DIR.glob("*.png"))
    examined=0
    for p in candidates:
        im=load_icon(p)
        if im is None: continue
        examined+=1
        for i,ref in enumerate(refs):
            results[i].append((score(ref,im),p.name))
    print("EXAMINED",examined,"ICONS",len(candidates))
    for i,ref in enumerate(refs):
        results[i].sort()
        print("MATCHES",LABELS[i],":", " | ".join(f"{name}:{s:.2f}" for s,name in results[i][:35]),flush=True)
    # Save a contact sheet for inspecting candidates after the ranked report.
    from PIL import ImageDraw
    sheet=Image.new("RGB",(12*92,6*100),(20,20,20))
    draw=ImageDraw.Draw(sheet)
    for row in range(6):
        for j,(_,name) in enumerate(results[row][:12]):
            with Image.open(ICON_DIR/name) as im:
                icon=im.convert("RGBA")
                icon.thumbnail((67,70))
                canvas=Image.new("RGBA",(70,72),(0,0,0,255))
                canvas.alpha_composite(icon,((70-icon.width)//2,(72-icon.height)//2))
                x=j*92;y=row*100
                sheet.paste(canvas.convert("RGB"),(x+5,y+5))
                draw.text((x+5,y+79),name,fill=(240,240,240))
    sheet.save(ROOT/"tools/icon-match-results.png")
    print("SHEET saved: tools/icon-match-results.png")

if __name__=="__main__":main()
