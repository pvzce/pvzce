#!/usr/bin/env python3
"""Rebuild the six existing I, Zombie puzzles from original rule/layout tables.

Reference: Patoke/re-plants-vs-zombies, Lawn/Challenge.cpp IZombieInitLevel,
Lawn/Board.cpp InitSeedBank and Lawn/Plant.cpp GetCost. Algorithms are independently
implemented in IZombieGarden; these are content tables, not copied upstream code.
"""
import json
import shutil
from pathlib import Path

DATA = Path(__file__).resolve().parents[1] / 'pvzce-game/src/main/resources/data/pvzce'
CARDS = {
    1: ['basic_zombie', 'buckethead_zombie', 'football_zombie'],
    2: ['basic_zombie', 'door_zombie', 'buckethead_zombie'],
    3: ['basic_zombie', 'buckethead_zombie', 'miner_zombie'],
    4: ['basic_zombie', 'buckethead_zombie', 'i_zombie_ladder'],
    5: ['basic_zombie', 'buckethead_zombie', 'bungee_zombie', 'balloon_zombie'],
    'endless': ['imp', 'conehead_zombie', 'i_zombie_pole_vaulter', 'buckethead_zombie',
                'bungee_zombie', 'miner_zombie', 'i_zombie_ladder', 'football_zombie', 'dancing_zombie'],
}
# Original rows run top-to-bottom; project rows run bottom-to-top.
FIXED = {
    1: [('sunflower', 3, 2), ('sunflower', 3, 3)],
    2: [('spikeweed', 3, 0), ('sunflower', 2, 0), ('sunflower', 3, 3)],
    3: [('potato_mine', 3, 0), ('sunflower', 2, 0), ('potato_mine', 2, 2),
        ('sunflower', 2, 4), ('torchwood', 3, 3)],
    4: [('wall_nut', 3, y) for y in range(5)] + [('sunflower', 2, y) for y in (0, 2, 4)],
    5: [('sunflower', 3, 2), ('sunflower', 3, 3)],
}
GROUPS = {
    1: [('sunflower', 7), ('squash', 3), ('pea_shooter', 6), ('snow_pea', 2)],
    2: [('spikeweed', 1, 0), ('pea_shooter', 1, 0), ('snow_pea', 2, 3), ('sunflower', 1, 3),
        ('sunflower', 4), ('spikeweed', 2), ('snow_pea', 2), ('pea_shooter', 4)],
    3: [('torchwood', 2), ('sunflower', 5), ('pea_shooter', 7), ('split_pea', 1)],
    4: [('pea_shooter', 1, 0), ('snow_pea', 1, 1), ('fume_shroom', 2, 2), ('snow_pea', 1, 3),
        ('pea_shooter', 1, 4), ('pea_shooter', 2), ('sunflower', 4)],
    5: [('cactus', 1, 1), ('cactus', 1, 4), ('magnet_shroom', 1), ('sunflower', 5),
        ('pea_shooter', 8), ('snow_pea', 2)],
}


def write(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')


def rebuild():
    repo = Path(__file__).resolve().parents[1]
    source = repo / 'refer/im7/images/brain.png'
    if source.exists():
        target = repo / 'local-assets/assets/pvzce/textures/gui/hud/i_zombie_brain.png'
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    # Existing versus cards use brains; the puzzle's ladder is separately priced in sun.
    for name, zombie, price in [('door_zombie', 'door_zombie', 100), ('miner_zombie', 'miner_zombie', 125),
                                ('bungee_zombie', 'bungee_zombie', 125), ('balloon_zombie', 'balloon_zombie', 150),
                                ('imp', 'imp', 50), ('dancing_zombie', 'dancing_zombie', 350),
                                ('i_zombie_ladder', 'ladder', 150), ('i_zombie_pole_vaulter', 'pole_vaulter_zombie', 75)]:
        write(DATA / 'slots' / (name + '.json'), {
            'id': 'pvzce:' + name, 'kind': 'zombie', 'content': 'pvzce:' + zombie,
            'cost': {'resources': {'pvzce:sun': price}, 'cooldown': 0},
            'icon': 'pvzce:textures/gui/cards/' + zombie,
        })
    local_resources = repo / 'local-assets'
    if (local_resources / 'assets/pvzce/animations').exists():
        from render_controller_icons import render_entity
        for zombie in ('door_zombie', 'miner_zombie', 'bungee_zombie', 'balloon_zombie', 'imp', 'dancing_zombie'):
            icon = render_entity(zombie, local_resources, 'pvzce')
            shutil.copyfile(icon, DATA.parent.parent / 'assets/pvzce/textures/gui/cards' / icon.name)
    for number, cards in CARDS.items():
        path = DATA / 'levels/yard/puzzle' / f'i_zombie_{number}.json'
        data = json.loads(path.read_text())
        endless = number == 'endless'
        data.update(slots=['pvzce:' + card for card in cards], max_seed_slots=len(cards), initial_sun=150,
                    seed_screen=False, buffs=[])
        data['rules'].update({'pvzce:seed_cooldown_multiplier': 0, 'pvzce:graves_spawn_night': False})
        garden = {'type': 'pvzce:plant_garden', 'brains': True, 'endless': endless,
                  'columns': 5 if endless else 4, 'initial_delay_ticks': 90}
        if endless:
            data.pop('initial_entities', None)
        else:
            data['initial_entities'] = [{'kind': 'plant', 'id': 'pvzce:' + name, 'x': x, 'y': 4 - y}
                                        for name, x, y in FIXED[number]]
            garden['formation'] = [{'plants': ['pvzce:' + group[0]], 'count': group[1], 'sun': 0,
                                    'rows': [4 - group[2]] if len(group) == 3 else []}
                                   for group in GROUPS[number]]
        data['mechanics'] = [garden, {'type': 'pvzce:mower', 'rows': []}, {'type': 'pvzce:rake', 'rows': []}]
        data['music']['cues'][0]['event'] = 'pvzce:music/cerebrawl'
        data['description'] = ('指挥僵尸吃掉五行脑子。普通僵尸只能投放在红线右侧，蹦极僵尸投放在线左侧。'
                               '向日葵受伤掉落阳光，不会自动生产；没有小推车，僵尸卡无冷却。'
                               + ('每吃完五个脑子就换一片随机花园，阳光结余保留，不额外补给，也没有最终轮。' if endless else '吃完全部五个脑子获胜。'))
        data['hints'] = [{'trigger': 'on_start', 'text': '吃掉五行脑子才能过关；先吃向日葵获取阳光。', 'duration_ticks': 600},
                         {'trigger': 'on_start', 'text': '普通僵尸放在红线右侧；蹦极僵尸放在线左侧。', 'duration_ticks': 600},
                         {'trigger': 'on_card_refused'}]
        write(path, data)


if __name__ == '__main__':
    rebuild()
