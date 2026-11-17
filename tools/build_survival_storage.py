"""Small survival-friendly chest room: isolated filters, sealed hopper transport, no clocks or shulkers."""
from pathlib import Path
import collections
import hashlib
import json
import os
import shutil
import sys
from build_request_storage import Build, integer, german_name

MATERIALS=['cobblestone','cobbled_deepslate','dirt','sand','gravel','oak_log',
           'spruce_log','oak_planks','coal','iron_ingot','redstone','bone_meal',
           'stone','glass','copper_ingot','gold_ingot','diamond','emerald',
           'lapis_lazuli','quartz','netherrack','rotten_flesh','gunpowder','string']
BAYS=len(MATERIALS)//2
BACK=2*BAYS+2
ROOF=7
FACING=['north','east','south','west']

def room():
    b=Build();modules=[]
    # Include the clear interior so importing into a prepared site does not leave obstructing blocks.
    for x in range(-1,16):
        for y in range(-1,ROOF+1):
            for z in range(BACK+2):
                block='air'
                if y==-1:block='stone'
                elif y==0:block='spruce_planks' if 5<=x<=9 else 'stone_bricks'
                elif y==ROOF:block='spruce_planks'
                elif x in (-1,15) or z in (0,BACK+1):block='stone_bricks' if y==1 else 'spruce_planks'
                b.put(x,y,z,block)
    # Timber frame and a bright central aisle; no expensive decorative blocks.
    for x in (-1,5,9,15):
        for z in (0,BACK+1):
            for y in range(1,ROOF):b.put(x,y,z,'stripped_spruce_log',axis='y')
    for x in range(5,10):
        for z in range(1,BACK+1):b.put(x,0,z,'polished_andesite' if x==7 else 'spruce_planks')
    for z in range(4,BACK,4):b.put(7,ROOF-1,z,'lantern',hanging=True,waterlogged=False)
    # Recessed entrance keeps the two access steps inside the small footprint.
    for y in (0,1,2):b.put(7,y,0,'air')
    b.put(7,-1,0,'spruce_stairs',half='bottom',shape='straight',facing='south',waterlogged=False)
    b.put(7,0,1,'spruce_stairs',half='bottom',shape='straight',facing='south',waterlogged=False)
    b.put(7,1,2,'spruce_door',half='lower',hinge='left',facing='north',open=False,powered=False)
    b.put(7,2,2,'spruce_door',half='upper',hinge='left',facing='north',open=False,powered=False)
    for x in (6,8):b.put(x,2,0,'glass_pane',north=False,south=False,east=True,west=True,waterlogged=False)

    def local(side,u,y,v):return (4-v,y+1,2+u) if side==0 else (10+v,y+1,BACK-1-u)
    def put(side,u,y,v,name,**props):
        if props.get('facing') in FACING:props['facing']=FACING[(FACING.index(props['facing'])+(1 if side==0 else -1))%4]
        b.put(*local(side,u,y,v),name,**props)
    for i,item in enumerate(MATERIALS):
        side=i//BAYS;u=(i%BAYS)*2
        p=lambda y,v,name,**props:put(side,u,y,v,name,**props)
        # Each 2-wide chest bay has an electrically isolated filter, allowing a cheap one-item filter.
        for y,v in [(1,1),(1,2),(1,3),(0,2),(0,4),(-1,3)]:p(y,v,'stone_bricks')
        p(2,0,'hopper',facing='north',enabled=True)
        b.inv(*local(side,u,2,0),[(0,item,1,False)]+[(slot,'cobblestone',11,True) for slot in range(1,5)])
        p(1,0,'hopper',facing='north',enabled=False)
        b.inv(*local(side,u,1,0),[(0,item,1,False)])
        p(2,1,'comparator',facing='north',mode='compare',powered=True)
        b.data[local(side,u,2,1)]={'OutputSignal':integer(2)}
        for y,v in [(2,2),(2,3),(1,4)]:p(y,v,'redstone_wire')
        p(0,3,'repeater',facing='south',delay=1,locked=False,powered=False)
        p(0,1,'redstone_wall_torch',facing='north',lit=True)
        p(1,-1,'chest',facing='north',type='left',waterlogged=False)
        put(side,u+1,1,-1,'chest',facing='north',type='right',waterlogged=False)
        # Feed both adjacent chest rows through a rear branch, leaving the front gap-free.
        p(0,0,'hopper',facing='north',enabled=False)
        p(0,-1,'chest',facing='north',type='left',waterlogged=False)
        put(side,u+1,0,-1,'chest',facing='north',type='right',waterlogged=False)
        # Stair headers leave lids accessible and hide the circuitry behind the timber facade.
        for du in (0,1):put(side,u+du,2,-1,'spruce_stairs',facing='north',half='top',shape='straight',waterlogged=False)
        frame=local(side,u,0,-2);b.frames.append((*frame,item,5 if side==0 else 4,False))
        sign=local(side,u+1,2,-2);label=german_name(item)
        b.sign(*sign,[f'{i+1:02d} {label[:12]}',label[12:27], '108 Stacks', 'automatisch'],facing='east' if side==0 else 'west')
        modules.append({'item':item,'chest':list(local(side,u,0,-1)), 'upper_chest':list(local(side,u,1,-1)), 'connector':list(local(side,u,0,0)), 'filter':list(local(side,u,2,0)),
                        'extractor':list(local(side,u,1,0)), 'transport':list(local(side,u,3,0))})
    # One continuous hopper line visits both walls. Every item stays inside an inventory.
    path=[(6,BACK),(5,BACK)]+[(4,z) for z in range(BACK,0,-1)]+[(x,1) for x in range(5,11)]+[(10,z) for z in range(2,BACK+1)]+[(9,BACK),(8,BACK)]
    for i,(x,z) in enumerate(path):
        if i==len(path)-1:f='down'
        else:
            nx,nz=path[i+1];f='east' if nx>x else 'west' if nx<x else 'south' if nz>z else 'north'
        b.put(x,4,z,'hopper',facing=f,enabled=True)
    b.put(8,3,BACK,'hopper',facing='down',enabled=True)
    b.put(8,2,BACK,'chest',facing='west',type='single',waterlogged=False)
    b.put(6,5,BACK,'chest',facing='north',type='left',waterlogged=False)
    b.put(7,5,BACK,'chest',facing='north',type='right',waterlogged=False)
    for x in (6,7,8):b.put(x,2,1,'spruce_trapdoor',half='top',facing='north',open=False,powered=False,waterlogged=False)
    for y in range(1,5):b.put(7,y,BACK,'bookshelf' if y==2 else 'spruce_planks')
    b.put(6,3,BACK,'spruce_trapdoor',half='bottom',facing='north',open=True,powered=False,waterlogged=False)
    b.sign(7,6,BACK,['EINWURF','Items hier hinein','24 Materialien','automatisch'])
    b.put(10,3,BACK,'spruce_planks')
    b.sign(9,3,BACK,['RESTEKISTE','andere Items / voll','hier abholen','nichts wird entsorgt'],facing='west')
    b.sign(7,5,1,['SURVIVAL-LAGER','24 Sortierfaecher','Einwurf: Rueckwand','erweiterbar'],facing='south')
    # Service access from above: remove one transport hopper to reach each filter, then replace it.
    for m in modules:
        x,_,z=m['filter'];b.put(x,ROOF,z,'spruce_trapdoor',half='top',facing='north',open=False,powered=False,waterlogged=False)
    counts=collections.Counter(s['Name'][1].split(':')[1] for s in b.blocks.values())
    meta={'modules':modules,'input':[6,5,BACK],'overflow':[8,2,BACK], 'door':[7,1,2],
          'capacity_items':len(MATERIALS)*108*64,'hopper_count':counts['hopper'],'block_counts':dict(counts)}
    return b,meta

def install(source):
    name='Kompakter Survival-Kistenraum mit Autosortierung (24 Materialien, 2 Doppelkisten je Fach).nbt'
    entry={'id':'survival_storage_24','source':'Begleiter custom survival chest room','file':name,
           'purpose':'Kleiner Kistenraum mit einer Einwurfkiste, 24 automatischen Sortierfächern mit je zwei Doppelkisten und sicherer Restekiste.',
           'operation':'Items in die Einwurfkiste an der Rückwand legen. Direkt aus den beschrifteten Doppelkisten entnehmen.',
           'requirement':'Survival: je Fach 2 Zielitems + 44 benannte Füllsteine. Filter über Dachluke erreichen; Transporttrichter kurz abnehmen und ersetzen.',
           'checked_by_user':False,'sha256':hashlib.sha256(source.read_bytes()).hexdigest().upper(),'legacy_files':{},
           'audit':'automatic-sorting-overflow-and-full-storage-operation-checked'}
    repo=Path(__file__).resolve().parents[1];roots=[repo]
    # Optional zweiter Checkout, der dieselben Ressourcen bekommen soll.
    if os.environ.get('BEGLEITER_SYNC_ROOT'):roots.append(Path(os.environ['BEGLEITER_SYNC_ROOT']).resolve())
    for root in roots:
        resources=root/'src/main/resources';data=resources/'data/begleiter'
        shutil.copy2(source,data/'bundled_schematics'/name)
        cat=data/'schematic_catalogue.json';entries=json.loads(cat.read_text(encoding='utf-8'))
        entries=[e for e in entries if e['id']!=entry['id']]+[entry]
        cat.write_text(json.dumps(entries,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        for locale,title,desc in [('de_de','Kompakter Survival-Kistenraum (24 Materialien, 2 Doppelkisten je Fach)',entry['purpose']),
             ('en_us','Compact survival chest room (24 materials, two double chests per bay)','A compact chest room with one input, 24 automatic sorting bays with two double chests each and a safe overflow chest.')]:
            path=resources/f'assets/begleiter/lang/{locale}.json';lang=json.loads(path.read_text(encoding='utf-8'))
            lang['server.begleiter.schematic.catalogue.survival_storage_24.name']=title
            lang['server.begleiter.schematic.catalogue.survival_storage_24.description']=desc
            path.write_text(json.dumps(lang,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

if __name__=='__main__':
    b,meta=room();out=Path('build/survival-storage');out.mkdir(parents=True,exist_ok=True)
    meta['min']=b.save(out/'compact.nbt');(out/'compact.json').write_text(json.dumps(meta,indent=2))
    print(json.dumps({k:meta[k] for k in ('hopper_count','capacity_items','min')}))
    if '--install' in sys.argv:install(out/'compact.nbt')
