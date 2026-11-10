#!/usr/bin/env python3
"""Extrai o vídeo H.264 de uma ou mais partes de uma gravação de sessão do REau (.aasession) para <saida>/<tag>.h264 + .csv + .start.

O .csv sai no formato novo (cabeçalho `frame,pts_ms,bytes_in_file,key`, tamanho no arquivo com o código de início; antes de 03/11/2026 saía `bytes` sem os 4 bytes): ver indice_video.py.
Uso: extrair.py SAIDA TAG PARTE1.aasession [PARTE2.aasession ...]   (as partes em ordem de tempo: a ".1" primeiro)
A cópia que a Marca do piloto congela (plano 22) tem o mesmo par, com outro nome: reau_<data>_<hora>_marca.1.aasession e reau_<data>_<hora>_marca.aasession.
O formato é o de SessionRecorder.kt: 'REAA', versão (1), hora do relógio da 1ª mensagem (8, BE); depois, por mensagem:
ms desde a 1ª (4, BE), canal (1), flags (1), tamanho (4), carga (carga[0:2] = tipo). Canal 2 é o vídeo: tipo 0 = quadro, tipo 1 = configuração (SPS/PPS)."""
import struct, sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))   # com `python3 -I` a pasta do script não entra sozinha
import indice_video

def ler(caminho):
    d = open(caminho, 'rb').read()
    assert d[:4] in (b'REAA', b'PGAA'), 'não é uma sessão'   # PGAA: a assinatura antiga das gravações de 15/10/2026, o resto do formato é o mesmo
    inicio = struct.unpack('>q', d[5:13])[0]
    pos, msgs = 13, []
    while pos + 10 <= len(d):
        t, ch, fl, tam = struct.unpack('>iBBi', d[pos:pos + 10]); pos += 10
        if tam < 0 or pos + tam > len(d): break
        msgs.append((t, ch, fl, d[pos:pos + tam])); pos += tam
    return inicio, msgs

def juntar(msgs):
    """Junta os pedaços de uma mensagem (flags: bit0 primeiro, bit1 último)."""
    pend, out = {}, []
    for t, ch, fl, p in msgs:
        primeiro, ultimo = fl & 1, fl & 2
        if primeiro and ultimo: out.append((t, ch, fl, p)); continue
        if primeiro: pend[ch] = [t, fl, bytearray(p)]; continue
        if ch not in pend: continue            # continuação órfã (a parte começou no meio)
        pend[ch][2] += p
        if ultimo: out.append((t, ch, pend[ch][1], bytes(pend[ch][2]))); del pend[ch]
    return out

def tipo(p): return (p[0] << 8) | p[1] if len(p) >= 2 else -1

def tem_idr(b):
    i = 0
    while True:
        j = b.find(b'\x00\x00\x01', i)
        if j < 0 or j + 3 >= len(b): return False
        if (b[j + 3] & 0x1f) == 5: return True
        i = j + 3

def main():
    saida, tag, partes = sys.argv[1], sys.argv[2], sys.argv[3:]
    quadros, cfg, t0 = [], None, None
    for f in partes:
        inicio, msgs = ler(f)
        if t0 is None: t0 = inicio
        for t, ch, fl, p in juntar(msgs):
            if ch != 2: continue
            ty = tipo(p)
            if ty == 1 and cfg is None: cfg = p[2:]
            elif ty == 0: quadros.append((inicio - t0 + t, p[10:]))
    if cfg is None:      # a parte pode ter começado depois da configuração: usa a de uma sessão conhecida do mesmo Android Auto
        cfg = bytes.fromhex(os.environ.get('CFG_HEX', ''))
        assert cfg, 'sem configuração SPS/PPS na gravação (defina CFG_HEX)'
    k = next((i for i, (t, u) in enumerate(quadros) if tem_idr(u)), None)
    assert k is not None, 'sem quadro-chave'
    quadros = quadros[k:]
    os.makedirs(saida, exist_ok=True)
    with open(f'{saida}/{tag}.h264', 'wb') as o, open(f'{saida}/{tag}.csv', 'w') as c:
        c.write(indice_video.CABECALHO_NOVO + '\n')   # bytes_in_file: o tamanho no arquivo, código de início incluído (a soma é o tamanho do .h264)
        for i, (t, u) in enumerate(quadros):
            b = (cfg if i == 0 else b'') + u
            o.write(b); c.write('%d,%d,%d,%d\n' % (i + 1, t, len(b), 1 if tem_idr(u) else 0))
    open(f'{saida}/{tag}.start', 'w').write(str(t0))
    print(tag, len(quadros), 'quadros; cfg', cfg.hex()[:24])

main()
