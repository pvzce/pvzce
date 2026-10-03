#!/usr/bin/env python3
"""Draw the original water-bucket tool and its small pouring controller."""
from pathlib import Path
import json
from PIL import Image, ImageDraw
ROOT = Path(__file__).resolve().parents[1] / 'pvzce-game/src/main/resources/assets/pvzce'

def main():
    image = Image.new('RGBA', (256, 256)); d = ImageDraw.Draw(image)
    ink = '#304550'
    d.arc((58, 24, 198, 187), 175, 365, fill=ink, width=13)
    d.arc((62, 28, 194, 183), 178, 362, fill='#b6c9ce', width=6)
    d.polygon([(42, 102), (214, 102), (191, 218), (163, 232), (91, 232), (64, 218)], fill='#6b929e', outline=ink, width=7)
    d.polygon([(61, 118), (90, 128), (102, 217), (77, 210)], fill='#a8c7cc')
    d.polygon([(179, 120), (205, 114), (186, 212), (158, 225)], fill='#496b7e')
    d.ellipse((39, 79, 216, 129), fill='#c7e0df', outline=ink, width=7)
    d.ellipse((52, 89, 204, 118), fill='#329dd3', outline='#19647d', width=4)
    d.arc((65, 95, 172, 111), 190, 355, fill='#b9f2ff', width=4)
    d.ellipse((56, 99, 66, 109), fill='#dce9e6', outline=ink, width=3)
    d.ellipse((192, 99, 202, 109), fill='#dce9e6', outline=ink, width=3)
    d.arc((76, 194, 182, 225), 5, 170, fill='#b2cdd0', width=4)
    image = image.resize((128, 128), Image.Resampling.LANCZOS)
    for relative in ('textures/gui/cards/water_bucket.png', 'textures/entities/tool/water_bucket.png'):
        path=ROOT/relative;path.parent.mkdir(parents=True,exist_ok=True);image.save(path)
    def pose(translation,rotation):
        return {'translation':translation,'rotation':rotation,'scale':{'0.0':[1,1]},'visible':{'0.0':True}}
    identity=pose({'0.0':[0,0]},{'0.0':[0,0,0]})
    controller={'type':'controller','model':{'size':[0.8,0.8],'bones':[{'name':'root','parent':None,'pivot':[0,0]},{'name':'bucket','parent':'root','pivot':[0,0],'parts':[{'texture':'pvzce:textures/entities/tool/water_bucket','uv':[0,0,128,128],'size':[0.8,0.8],'offset':[0,0],'z':0}]}]},'animations':{'idle':{'animation_length':1,'loop':True,'transition':0.1,'bones':{'root':identity,'bucket':identity}},'attack':{'animation_length':0.65,'loop':False,'on_end':'idle','transition':0.05,'bones':{'root':identity,'bucket':pose({'0.0':[0,0],'0.18':[0.05,0.04],'0.45':[0.05,0.04],'0.65':[0,0]},{'0.0':[0,0,0],'0.18':[0,0,-35],'0.45':[0,0,-35],'0.65':[0,0,0]})}}}}
    path=ROOT/'animations/tool/water_bucket.json';path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(controller,indent=2)+'\n')

if __name__ == '__main__': main()
