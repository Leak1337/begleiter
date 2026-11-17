"""Reproducible vanilla redstone storage blueprint. No commands or runtime inventory scripts."""
import gzip
import json
import struct
import hashlib
import os
import shutil
import sys
from pathlib import Path

MATERIALS = '''stone cobblestone smooth_stone deepslate cobbled_deepslate polished_deepslate tuff calcite granite polished_granite diorite polished_diorite andesite polished_andesite dirt grass_block coarse_dirt rooted_dirt mud clay sand red_sand gravel sandstone red_sandstone netherrack basalt blackstone end_stone obsidian crying_obsidian glass
oak_log spruce_log birch_log jungle_log acacia_log dark_oak_log mangrove_log cherry_log pale_oak_log bamboo_block crimson_stem warped_stem oak_planks spruce_planks birch_planks jungle_planks acacia_planks dark_oak_planks mangrove_planks cherry_planks pale_oak_planks bamboo_planks crimson_planks warped_planks
iron_ingot gold_ingot copper_ingot raw_iron raw_gold raw_copper coal charcoal diamond emerald lapis_lazuli redstone quartz amethyst_shard netherite_scrap netherite_ingot
redstone_torch redstone_block repeater comparator observer piston sticky_piston hopper dropper dispenser lever torch rail powered_rail target crafter
wheat wheat_seeds beetroot beetroot_seeds carrot potato sugar_cane bamboo cactus kelp dried_kelp bone_meal bone gunpowder string rotten_flesh
arrow flint leather paper book slime_ball honeycomb glowstone_dust
white_wool orange_wool magenta_wool light_blue_wool yellow_wool lime_wool pink_wool gray_wool light_gray_wool cyan_wool purple_wool blue_wool brown_wool green_wool red_wool black_wool'''.split()
assert len(MATERIALS)==128 and len(set(MATERIALS))==128

def german_name(item):
    if not hasattr(german_name,'labels'):
        german_name.labels={row['id']:row['name_de'] for row in json.loads(Path(__file__).with_name('request_storage_materials.json').read_text(encoding='utf-8'))}
    return german_name.labels[item]

def byte(v): return (1, v)
def integer(v): return (3, v)
def string(v): return (8, v)
def compound(v): return (10, v)
def sequence(kind, values): return (9, (kind, values))
def text(v):
    b = v.encode('utf-8'); return struct.pack('>H', len(b)) + b
def payload(kind, value):
    if kind in (1, 2, 3, 4, 6): return struct.pack({1:'>b',2:'>h',3:'>i',4:'>q',6:'>d'}[kind],value)
    if kind == 8: return text(value)
    if kind == 9:
        sub, values=value
        return bytes([sub])+struct.pack('>i',len(values))+b''.join(payload(sub,v) for v in values)
    if kind == 10: return b''.join(bytes([k])+text(n)+payload(k,v) for n,(k,v) in value.items())+b'\0'
    raise ValueError(kind)

class Build:
    def __init__(self): self.blocks={}; self.data={}; self.frames=[]
    def put(self,x,y,z,name,**properties):
        if name=='redstone_wire':properties={'north':'side','south':'side','east':'side','west':'side','power':0,**properties}
        self.blocks[x,y,z]={'Name':string('minecraft:'+name)}
        if properties: self.blocks[x,y,z]['Properties']=compound({k:string(str(v).lower()) for k,v in properties.items()})
    def inv(self,x,y,z,items):
        self.data[x,y,z]={'Items':sequence(10,[dict(id=string('minecraft:'+item),count=integer(count),Slot=byte(slot),**(
            {'components':compound({'minecraft:custom_name':string('Begleiter Filter - nicht entfernen')})} if renamed else {}))
            for slot,item,count,renamed in items])}
    def sign(self,x,y,z,lines,facing='north'):
        self.put(x,y,z,'oak_wall_sign',facing=facing,waterlogged=False)
        self.data[x,y,z]={'front_text':compound({'messages':sequence(8,(lines+['']*4)[:4]),'color':string('black'),'has_glowing_text':byte(0)}),'is_waxed':byte(1)}
    def module(self,item='cobblestone',ox=0,oy=0,oz=0):
        local=Build();local._module(item)
        self.blocks.update({(x+ox,y+oy,z+oz):s for (x,y,z),s in local.blocks.items()})
        self.data.update({(x+ox,y+oy,z+oz):s for (x,y,z),s in local.data.items()})

    def _module(self,item='cobblestone',ox=0,oy=0,oz=0):
        # Keep the sorting torches clear of piston quasi-connectivity.
        upper=lambda y:y+3 if y>=5 else y
        p=lambda x,y,z,n,**props:self.put(x+ox,upper(y)+oy,z+oz,n,**props)
        inv=lambda x,y,z,v:self.inv(x+ox,upper(y)+oy,z+oz,v)
        p(0,0,0,'smooth_stone')
        # Subtract a constant comparator reference of 2; only surplus filter items unlock extraction.
        for x,y,z in [(3,7,0),(3,7,1),(3,7,2),(4,7,2),(5,7,2),(5,7,3),(5,6,4),(4,6,4),(4,5,3),(4,6,2)]:p(x,y,z,'smooth_stone')
        p(3,8,0,'composter',level=2)
        p(3,8,1,'comparator',facing='north',mode='compare',powered=False)
        self.data[3+ox,11+oy,1+oz]={'OutputSignal':integer(2)}
        p(3,8,2,'comparator',facing='west',mode='subtract',powered=False)
        p(4,8,2,'repeater',facing='west',delay=1,locked=False,powered=False)
        for x,y,z in [(5,8,2),(5,8,3),(5,7,4),(4,7,4)]:p(x,y,z,'redstone_wire')
        p(4,6,3,'repeater',facing='south',delay=1,locked=False,powered=False)
        p(3,6,2,'redstone_wall_torch',facing='west',lit=True)
        p(2,8,2,'hopper',facing='north',enabled=True)
        inv(2,8,2,[(0,item,41,False)]+[(i,'paper',1,True) for i in range(1,5)])
        p(2,7,2,'hopper',facing='down',enabled=False)
        inv(2,7,2,[(0,item,1,False)])
        p(2,6,2,'barrel',facing='up',open=False)
        p(2,5,2,'hopper',facing='down',enabled=True)
        for y in (5,6,7):self.put(2+ox,y+oy,2+oz,'hopper',facing='down',enabled=True)
        p(2,4,2,'shulker_box',facing='up')
        p(2,9,2,'barrel',facing='up',open=False)
        # Permanent side catcher: the piston pushes the broken box west, away from the loading inventory.
        # The earlier timed catcher could close before a late falling box had left its hopper.
        q=lambda x,y,z,n,**props:self.put(ox+x,oy+y,oz+z,n,**props)
        for y in (5,6,7):q(2,y,2,'hopper',facing='down',enabled=True)
        q(2,3,2,'glass');q(2,4,2,'shulker_box',facing='up')
        q(3,4,2,'piston',facing='west',extended=False)
        q(2,4,1,'dispenser',facing='south',triggered=False)
        self.inv(ox+2,oy+4,oz+1,[(i,'shulker_box',1,False) for i in range(9)])
        for x,z,f in [(1,2,'north'),(1,1,'north'),(1,0,'east'),(2,0,'east')]:q(x,3,z,'hopper',facing=f,enabled=True)
        q(3,3,0,'barrel',facing='up',open=False)
        q(1,4,2,'comparator',facing='east',mode='compare',powered=False)
        q(0,3,2,'smooth_stone');q(0,4,2,'repeater',facing='east',delay=1,locked=False,powered=False)
        q(-1,4,2,'smooth_stone');q(-1,4,1,'redstone_wall_torch',facing='north',lit=True)
        wires=[(-1,0),(-1,-1)]+[(x,-2) for x in range(-1,6)]
        for x,z in wires:q(x,3,z,'smooth_stone');q(x,4,z,'redstone_wire')
        for z in (-1,0,1):q(5,3,z,'smooth_stone');q(5,4,z,'repeater',facing='north',delay=4,locked=False,powered=True)
        q(5,3,2,'smooth_stone');q(5,4,2,'repeater',facing='east',delay=1,locked=False,powered=False)
        q(4,4,2,'smooth_stone');q(6,4,2,'smooth_stone')
        q(6,3,2,'stone_button',face='ceiling',facing='north',powered=False)
        # Retraction observer replaces the box after the piston head is gone; extension pulses are harmless.
        q(3,3,1,'smooth_stone');q(3,4,1,'observer',facing='south',powered=False)
        q(3,4,0,'repeater',facing='south',delay=4,locked=False,powered=False)
        q(3,4,-1,'smooth_stone');q(2,3,-1,'smooth_stone')
        q(2,4,-1,'repeater',facing='east',delay=1,locked=False,powered=False)
        q(1,4,-1,'smooth_stone');q(1,4,0,'repeater',facing='north',delay=1,locked=False,powered=False)
        q(1,4,1,'smooth_stone')
    def save(self,path):
        mins=[min(p[i] for p in self.blocks) for i in range(3)]
        maxs=[max(p[i] for p in self.blocks) for i in range(3)]
        palette=[]; keys={}; blocks=[]
        for pos,state in sorted(self.blocks.items()):
            key=repr(state)
            if key not in keys:keys[key]=len(palette);palette.append(state)
            b={'pos':sequence(3,[pos[i]-mins[i] for i in range(3)]),'state':integer(keys[key])}
            if pos in self.data:b['nbt']=compound(self.data[pos])
            blocks.append(b)
        entities=[]
        for frame in self.frames:
            x,y,z,item=frame[:4];facing=frame[4] if len(frame)>4 else 2
            fixed=frame[5] if len(frame)>5 else True
            px,py,pz=x+.5,y+.5,z+.5
            if facing==2:pz=z+.96875
            elif facing==3:pz=z+.03125
            elif facing==4:px=x+.96875
            elif facing==5:px=x+.03125
            entities.append({'pos':sequence(6,[px-mins[0],py-mins[1],pz-mins[2]]),
                'blockPos':sequence(3,[x-mins[0],y-mins[1],z-mins[2]]),
                'nbt':compound({'id':string('minecraft:item_frame'),'Facing':byte(facing),'Fixed':byte(int(fixed)),
                    'Item':compound({'id':string('minecraft:'+item),'count':integer(1)})})})
        root={'DataVersion':integer(5023),'size':sequence(3,[maxs[i]-mins[i]+1 for i in range(3)]),
              'palette':sequence(10,palette),'blocks':sequence(10,blocks),'entities':sequence(10,entities)}
        path.parent.mkdir(parents=True,exist_ok=True)
        path.write_bytes(gzip.compress(b'\x0a\0\0'+payload(10,root),mtime=0))
        return mins

    def water_path(self,path):
        """Covered ice stream with inset chest walls aligning items over adjacent filters."""
        cells=set(path);corners=[0]
        for i in range(1,len(path)-1):
            if (path[i][0]-path[i-1][0],path[i][1]-path[i-1][1])!=(path[i+1][0]-path[i][0],path[i+1][1]-path[i][1]):corners.append(i)
        corners.append(len(path)-1)
        levels={}
        for a,b in zip(corners,corners[1:]):
            for i in range(a,b):levels[i]=None if (i-a)%8==7 else (i-a)%8
            if b>0:levels[b-1]=None
        levels[len(path)-1]=1
        for i,(x,z) in enumerate(path):
            self.put(x,11,z,'blue_ice')
            if levels[i] is None:self.put(x,12,z,'oak_sign',rotation=0,waterlogged=False)
            else:self.put(x,12,z,'water',level=levels[i])
            self.put(x,13,z,'glass')
            for dx,dz in ((1,0),(-1,0),(0,1),(0,-1)):
                if (x+dx,z+dz) not in cells:
                    if dx==1:
                        if self.blocks.get((x+1,11,z),{}).get('Name')==string('minecraft:hopper'):
                            self.put(x+1,12,z,'smooth_stone_slab',type='top',waterlogged=False)
                        else:self.put(x+1,12,z,'chest',facing='west',type='single',waterlogged=False)
                    else:self.put(x+dx,12,z+dz,'chest',facing='west',type='single',waterlogged=False)

    def input_clock(self,x,z):
        # Comparator subtract clock, hopper metered: at most 2.5 items/s, even a homogeneous batch is safe.
        self.put(x,13,z,'dropper',facing='east',triggered=False)
        self.put(x,14,z,'hopper',facing='down',enabled=True)
        self.put(x,15,z,'barrel',facing='up',open=False)
        for dx,dz in ((-1,0),(-2,0),(-3,0),(-3,1),(-2,1),(-1,1),(0,1)):self.put(x+dx,12,z+dz,'smooth_stone')
        self.put(x-1,13,z,'comparator',facing='east',mode='subtract',powered=False)
        self.put(x-2,13,z,'repeater',facing='east',delay=1,powered=False,locked=False)
        self.put(x-3,13,z,'smooth_stone')
        for dx in (-3,-2,-1,0):self.put(x+dx,13,z+1,'redstone_wire')
        # A boosted side input makes this a true oscillator instead of an analog equilibrium.
        self.put(x-1,13,z+1,'repeater',facing='south',delay=1,powered=False,locked=False)
        self.put(x-1,12,z+2,'smooth_stone')
        self.put(x-2,12,z+2,'smooth_stone')
        self.put(x-3,12,z+2,'smooth_stone')
        self.put(x,12,z+2,'smooth_stone')
        for dx in (-3,-2,-1,0):self.put(x+dx,13,z+2,'redstone_wire')
        # A gated observer clock runs faster than the metering hopper, so items do not queue in the dropper.
        for xx in range(x-4,x+1):
            for zz in range(z-4,z+3):
                self.put(xx,12,zz,'smooth_stone')
                self.blocks.pop((xx,13,zz),None)
        self.put(x,13,z,'dropper',facing='east',triggered=False)
        self.put(x,14,z,'hopper',facing='down',enabled=True)
        self.put(x,15,z,'barrel',facing='up',open=False)
        self.put(x-2,13,z-1,'observer',facing='south',powered=False)
        self.put(x-2,13,z,'observer',facing='north',powered=False)
        self.put(x-2,13,z+1,'repeater',facing='north',delay=1,locked=False,powered=False)
        self.put(x-2,13,z+2,'smooth_stone')
        for xx,zz in ((x-1,z+2),(x,z+2),(x,z+1)):self.put(xx,13,zz,'redstone_wire')
        self.put(x,13,z-1,'comparator',facing='south',mode='compare',powered=False)
        self.put(x,13,z-2,'smooth_stone');self.put(x,13,z-3,'redstone_wall_torch',facing='north',lit=True)
        for xx in range(x-4,x+1):self.put(xx,13,z-4,'redstone_wire')
        for zz in range(z-3,z+2):self.put(x-4,13,zz,'redstone_wire')
        self.put(x-3,13,z+1,'repeater',facing='west',delay=1,locked=False,powered=True)

    def unloader(self,ox=0,oy=0,oz=0):
        # BU003 by misterkartoffel and sergyd (Storage Catalog). Empty boxes and contents leave separately.
        p=lambda x,y,z,n,**props:self.put(x+ox,y+oy,z+oz,n,**props)
        for x,y,z in [(0,0,0),(2,0,0),(3,0,1),(2,0,2)]:p(x,y,z,'white_stained_glass')
        for x,y,z in [(1,1,0),(3,1,0),(0,1,1),(1,1,1),(3,1,2),(3,2,1)]:p(x,y,z,'white_concrete')
        p(0,0,2,'chest',facing='south',type='single',waterlogged=False)
        p(1,0,2,'chest',facing='south',type='single',waterlogged=False)
        p(0,1,0,'redstone_wire');p(3,1,1,'redstone_wire')
        p(2,1,0,'repeater',facing='east',delay=2,locked=False,powered=False)
        p(2,1,1,'observer',facing='east',powered=False)
        p(0,1,2,'hopper',facing='down',enabled=True)
        p(1,1,2,'hopper',facing='down',enabled=True)
        p(2,1,2,'comparator',facing='west',mode='compare',powered=False)
        p(1,2,0,'hopper',facing='south',enabled=True)
        p(1,2,1,'dispenser',facing='south',triggered=False)
        p(2,2,1,'comparator',facing='west',mode='compare',powered=False)
        p(1,2,2,'piston_head',facing='west',type='normal',short=False)
        p(2,2,2,'piston',facing='west',extended=True)
        p(3,2,2,'redstone_torch',lit=True)
        p(1,3,0,'chest',facing='south',type='single',waterlogged=False)

def network(items,cols):
    b=Build();rows=(len(items)+cols-1)//cols
    for x in range(-12,cols*8+7):
        for z in range(-8,rows*8+2):b.put(x,0,z,'smooth_stone');b.put(x,1,z,'smooth_stone')
    modules=[]
    for i,item in enumerate(items):
        x=(i%cols)*8;z=(i//cols)*8
        b.module(item,x,0,z)
        b.blocks.pop((x+2,12,z+2),None)
        # A second independent filter catches items that arrive during the first hopper's cooldown.
        upper=Build();upper.module(item)
        for (xx,yy,zz),state in upper.blocks.items():
            if 8<=yy<=11:b.blocks[x+xx,yy,z+zz+4]=state.copy()
        for (xx,yy,zz),tag in upper.data.items():
            if 8<=yy<=11:b.data[x+xx,yy,z+zz+4]=tag.copy()
        for zz in range(3,7):b.put(x+2,8,z+zz,'hopper',facing='north',enabled=True)
        # Drain the local box output into sealed hopper pipes under the service floor.
        b.put(x+3,2,z,'hopper',facing='down',enabled=True)
        label=german_name(item)
        b.sign(x+7,2,z+2,[f'{i+1:03d} Abrufen',label[:18],label[18:36], '1 Shulkerbox'])
        b.put(x+7,2,z+2,'oak_sign',rotation=8,waterlogged=False)
        modules.append({'item':item,'box':[x+2,4,z+2],'button':[x+6,3,z+2],'filter':[x+2,11,z+2], 'catcher':[x+2,3,z+2]})
    # Each row drains toward the centre; the central trunk goes to a single output in the entrance lobby.
    trunk=cols*4
    for row in range(rows):
        for x in range(3,cols*8):b.put(x,1,row*8,'hopper',facing=('east' if x<trunk else 'west'),enabled=True)
    for z in range(-6,(rows-1)*8+1):b.put(trunk,1,z,'hopper',facing='north',enabled=True)
    b.put(trunk,1,-7,'barrel',facing='north',open=False)
    for x in range(trunk-3,trunk+4):
        for z in range(-8,-3):b.put(x,0,z,'quartz_block')
    path=[];front=-6;back=rows*8+2
    for col in range(cols):
        x=1+col*8
        zz=range(front,back+1) if col%2==0 else range(back,front-1,-1)
        path.extend((x,z) for z in zz)
        if col<cols-1:
            end=back if col%2==0 else front
            path.extend((x+d,end) for d in range(1,8))
    b.water_path(path)
    ex,ez=path[-1]
    b.put(ex,11,ez,'hopper',facing='down',enabled=True)
    b.put(ex,10,ez,'barrel',facing='up',open=False)
    b.input_clock(0,-6)
    # Do not cap the dropper's mouth with the stream side wall.
    # Drop from the metered outlet into the lower stream.
    b.put(1,13,-6,'air')
    b.put(1,14,-6,'glass')
    b.put(2,13,-6,'chest',facing='west',type='single',waterlogged=False);b.put(1,13,-7,'glass')
    meta={'modules':modules,'input':[0,15,-6],'output':[trunk,1,-7],'overflow':[ex,10,ez], 'stream_start':[1,12,-6]}
    return b,meta

def complete(items,cols):
    b,meta=network(items,cols);rows=(len(items)+cols-1)//cols;trunk=cols*4;lift=5
    b.blocks={(x,y+lift,z):s for (x,y,z),s in b.blocks.items()};b.data={(x,y+lift,z):s for (x,y,z),s in b.data.items()}
    for key in ('input','output','overflow','stream_start'):meta[key][1]+=lift
    for module in meta['modules']:
        for key in ('box','button','filter','catcher'):module[key][1]+=lift
    def empty_hopper(x,y,z,facing):
        b.put(x,y,z,'hopper',facing=facing,enabled=True)
        b.inv(x,y,z,[(i,'shulker_box',1,False) for i in range(5)])
    # Closed, prefilled return network: each dispenser vacancy is replenished without starving distant cells.
    path=[(x,-4) for x in range(-8,2)]+[(1,z) for z in range(-3,7)]
    for row in range(rows):
        z=7+row*8
        path.extend((x,z) for x in (range(1,cols*8+2) if row%2==0 else range(cols*8+1,0,-1)))
        if row<rows-1:path.extend(((cols*8+1 if row%2==0 else 1),z+d) for d in range(1,8))
    endx,endz=path[-1]
    path.extend((x,endz) for x in range(endx-1,-7,-1))
    path.extend((-6,z) for z in range(endz-1,-5,-1))
    path.append((-7,-4))
    for i,(x,z) in enumerate(path):
        nx,nz=path[(i+1)%len(path)];facing='east' if nx>x else 'west' if nx<x else 'south' if nz>z else 'north'
        empty_hopper(x,12,z,facing)
    for i,item in enumerate(items):
        x=i%cols*8;z=i//cols*8
        for zz in range(z+1,z+8):empty_hopper(x+1,11,zz,'east' if zz==z+1 else 'north')
        empty_hopper(x+2,11,z+1,'down');empty_hopper(x+2,10,z+1,'down')
        # Frame is next to the sign, outside every redstone support and wire.
        b.put(x+7,8,z+4,'smooth_stone');b.frames.append((x+7,8,z+3,item))
        assert b.blocks.get((x+7,8,z+3),{}).get('Name',string('minecraft:air'))==string('minecraft:air'),'Frame intersects a support block'
    # Incoming boxes are automatically emptied into the ordinary metered input.
    b.unloader(-8,24,-6)
    b.put(-8,24,-4,'chest',facing='east',type='left',waterlogged=False)
    b.put(-8,24,-3,'chest',facing='east',type='right',waterlogged=False)
    for y in range(13,24):b.put(-8,y,-4,'hopper',facing='down',enabled=True)
    for x in range(-7,1):b.put(x,23,-4,'hopper',facing='east' if x<0 else 'north',enabled=True)
    b.put(0,23,-5,'hopper',facing='north',enabled=True)
    for y in range(21,24):b.put(0,y,-6,'hopper',facing='down',enabled=True)
    meta['box_input']=[-7,27,-6]
    # Exhibition lobby and automatic output unloader, with a physical switch for whole-box pickup.
    for x in range(trunk-5,trunk+5):
        for y in range(1,9):
            for z in range(-10,-3):b.put(x,y,z,'air');b.data.pop((x,y,z),None)
    for x in range(trunk-6,trunk+7):
        for z in range(-15,-3):b.put(x,0,z,'quartz_block')
    b.unloader(trunk-1,3,-8)
    for z in range(-7,-3):b.put(trunk,6,z,'hopper',facing='north',enabled=True)
    b.put(trunk-1,3,-6,'chest',facing='east',type='left',waterlogged=False)
    b.put(trunk-1,3,-5,'chest',facing='east',type='right',waterlogged=False)
    b.put(trunk,3,-6,'chest',facing='east',type='left',waterlogged=False)
    b.put(trunk,3,-5,'chest',facing='east',type='right',waterlogged=False)
    b.put(trunk,5,-9,'smooth_stone')
    b.put(trunk,5,-10,'lever',face='wall',facing='north',powered=False)
    meta['output']=[trunk,3,-6];meta['box_output']=[trunk,6,-8];meta['mode_switch']=[trunk,5,-10];meta['automatic']=True
    # Empty output boxes rise in a bubble elevator into two double-chest buffers, then return to the loaders.
    b.put(trunk-1,2,-6,'hopper',facing='north',enabled=True)
    for z in range(-11,-6):b.put(trunk-1,2,z,'hopper',facing='north',enabled=True)
    b.put(trunk-1,2,-12,'hopper',facing='down',enabled=True)
    clock=Build();clock.input_clock(0,0)
    for (x,y,z),state in clock.blocks.items():
        if y>13:continue
        props={k:v[1] for k,v in state.get('Properties',(10,{}))[1].items()}
        if props.get('facing') in ('east','west'):props['facing']='west' if props['facing']=='east' else 'east'
        b.put(trunk-1-x,y-12,-12+z,state['Name'][1].split(':')[1],**props)
    bx=trunk-2
    b.put(bx,0,-12,'soul_sand')
    for y in range(1,20):
        b.put(bx,y,-12,'bubble_column',drag=False)
        for xx,zz in ((bx-1,-12),(bx+1,-12),(bx,-13),(bx,-11)):
            if (xx,y,zz) not in b.blocks or b.blocks[xx,y,zz]['Name']==string('minecraft:air'):b.put(xx,y,zz,'glass')
    b.put(bx,20,-12,'water',level=0);b.put(bx-1,20,-12,'water',level=1)
    for xx,zz in ((bx+1,-12),(bx,-13),(bx,-11),(bx-1,-13),(bx-1,-11),(bx-2,-12)):b.put(xx,20,zz,'glass')
    b.put(bx,21,-12,'glass');b.put(bx-1,21,-12,'glass')
    b.put(bx-1,19,-12,'hopper',facing='west',enabled=True)
    for y in (19,17):
        b.put(bx-2,y,-12,'chest',facing='east',type='left',waterlogged=False)
        b.put(bx-2,y,-11,'chest',facing='east',type='right',waterlogged=False)
    for y in (18,16,15,14,13):b.put(bx-2,y,-12,'hopper',facing='down',enabled=True)
    for x in range(-8,bx-1):empty_hopper(x,12,-12,'west' if x>-8 else 'south')
    for z in range(-11,-4):empty_hopper(-8,12,z,'south')
    # Walkable access to both the controls and the high input balcony.
    for step in range(7):b.put(trunk-5,step,-10+step,'quartz_stairs',facing='south',half='bottom',shape='straight',waterlogged=False)
    for step in range(27):b.put(-11,step,-29+step,'quartz_stairs',facing='south',half='bottom',shape='straight',waterlogged=False)
    for x in range(-10,3):
        for z in range(-9,-1):
            if -8<=x<=-5 and -6<=z<=-4:continue
            if (x,26,z) not in b.blocks or b.blocks[x,26,z]['Name']==string('minecraft:air'):b.put(x,26,z,'smooth_stone')
    # Low-level loose-input access: a clearly marked service stair reaches the regular input at y20.
    for step in range(20):b.put(-13,step,-26+step,'quartz_stairs',facing='south',half='bottom',shape='straight',waterlogged=False)
    b.put(-11,26,-2,'smooth_stone')
    for x in range(-12,1):b.put(x,19,-7,'smooth_stone')
    b.put(-13,19,-6,'smooth_stone');b.put(-13,20,-6,'oak_fence',north=False,south=False,east=False,west=False,waterlogged=False)
    # Unknown/full overflow pauses the ordinary input before loose items can accumulate indefinitely.
    ex,ey,ez=meta['overflow']
    b.put(ex,ey-1,ez-1,'smooth_stone');b.put(ex,ey-1,ez-2,'smooth_stone');b.put(ex,ey,ez-3,'smooth_stone')
    b.put(ex,ey,ez-1,'comparator',facing='south',mode='compare',powered=False)
    b.put(ex,ey,ez-2,'repeater',facing='south',delay=1,locked=False,powered=False)
    for x in range(4,ex+1):
        b.put(x,ey,ez-3,'smooth_stone')
        b.put(x,ey+1,ez-3,'repeater' if (ex-x)%12==0 and x<ex else 'redstone_wire',**({'facing':'east','delay':1,'locked':False,'powered':False} if (ex-x)%12==0 and x<ex else {}))
    for x,z in ((4,-10),(4,-11),(3,-11)):
        b.put(x,15,z,'smooth_stone');b.put(x,16,z,'redstone_wire')
    for y,z in ((17,-10),(18,-9),(19,-8)):
        b.put(3,y-1,z,'smooth_stone');b.put(3,y,z,'redstone_wire')
    for x in (1,2):b.put(x,18,-8,'smooth_stone');b.put(x,19,-8,'redstone_wire')
    b.put(1,18,-7,'smooth_stone');b.put(1,19,-7,'repeater',facing='north',delay=1,locked=False,powered=False)
    b.put(1,19,-6,'smooth_stone');b.put(1,20,-6,'redstone_lamp',lit=False)
    for x,y,z,lines in [
        (trunk+4,2,-5,['Zentrale Ausgabe','AUS: lose Items','EIN: ganze Boxen','Hebel links']),
        (-9,1,-30,['Boxen-Eingang','obere Treppe','voll oder leer','automatisch']),
        (-15,1,-27,['Lose-Items-Eingang','untere Treppe','Lampe: Restekiste','leeren und weiter'])]:
        b.put(x,y,z+1,'smooth_stone');b.sign(x,y,z,lines)
    return b,meta

def install():
    name='Zentrallager 128 mit Sortierung und Itemabruf.nbt'
    source=Path('build/request-storage/complete.nbt')
    entry={'id':'request_storage_128','source':'Begleiter custom design; BU003 unloader by misterkartoffel and sergyd',
        'file':name,'purpose':'Sortiert 128 feste Materialtypen und liefert sie per Abruf an eine zentrale Ausgabe.',
        'operation':'Eingang oben befüllen. Materialknopf drücken. Ausgabehebel AUS: lose Items; EIN: ganze Boxen.',
        'requirement':'Creative ist vorgefüllt. Survival: Filter und Leerboxkreislauf füllen. Restekiste leeren, wenn die Eingangslampe leuchtet.',
        'checked_by_user':False,'sha256':hashlib.sha256(source.read_bytes()).hexdigest().upper(),
        'legacy_files':{},'audit':'end-to-end-sorting-retrieval-unloading-and-empty-return-tested'}
    current=Path(__file__).resolve().parents[1]; roots=[current]
    # Optional zweiter Checkout, der dieselben Ressourcen bekommen soll.
    if os.environ.get('BEGLEITER_SYNC_ROOT'):roots.append(Path(os.environ['BEGLEITER_SYNC_ROOT']).resolve())
    for root in roots:
        resources=root/'src/main/resources';data=resources/'data/begleiter'
        shutil.copy2(source,data/'bundled_schematics'/name)
        catalogue=data/'schematic_catalogue.json';entries=json.loads(catalogue.read_text(encoding='utf-8'))
        entries=[e for e in entries if e['id']!=entry['id']]+[entry]
        catalogue.write_text(json.dumps(entries,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        for locale,title,description in [('de_de','Zentrallager 128 mit Sortierung und Itemabruf',entry['purpose']),
            ('en_us','128-material central storage with sorting and retrieval','Automatically sorts 128 configured materials and delivers requested items to a central output.')]:
            path=resources/f'assets/begleiter/lang/{locale}.json';lang=json.loads(path.read_text(encoding='utf-8'))
            lang['server.begleiter.schematic.catalogue.request_storage_128.name']=title
            lang['server.begleiter.schematic.catalogue.request_storage_128.description']=description
            path.write_text(json.dumps(lang,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

if __name__=='__main__':
    b=Build();b.module();print(b.save(Path('build/request-storage/prototype.nbt')))
    b,meta=network(['cobblestone','dirt','oak_log','iron_ingot'],2)
    meta['min']=b.save(Path('build/request-storage/network.nbt'))
    Path('build/request-storage/network.json').write_text(json.dumps(meta,indent=2))
    b,meta=network(MATERIALS,8)
    meta['min']=b.save(Path('build/request-storage/full.nbt'))
    Path('build/request-storage/full.json').write_text(json.dumps(meta,indent=2))
    b=Build();b.unloader();b.save(Path('build/request-storage/unloader.nbt'))
    b,meta=complete(MATERIALS,8)
    meta['min']=b.save(Path('build/request-storage/complete.nbt'))
    Path('build/request-storage/complete.json').write_text(json.dumps(meta,indent=2))
    b,meta=complete(['cobblestone','dirt','oak_log','iron_ingot'],2)
    meta['min']=b.save(Path('build/request-storage/complete-mini.nbt'))
    Path('build/request-storage/complete-mini.json').write_text(json.dumps(meta,indent=2))
    if '--install' in sys.argv:install()
