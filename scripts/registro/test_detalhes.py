"""Tests of detalhes.py: the summary of the heartbeat and the comparison of the panel's group 03 between connections, from the `[detail]` packet lines (item 25, 11/11/2026)."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))
import detalhes  # noqa: E402
import ler_registro  # noqa: E402
from test_ler_registro import escreve, linha  # noqa: E402


def tx(hora, bateria=0xA9, musica=0x10, rumo="0000", temp=0x43, gps="55", icone="05", sinal="01", carga="AA", chamada="32", volume_chamada=0x1A):
    return (f"{hora}  +     0ms  [detail] dash tx 0608:{icone} 0610:{temp:02X} 0603:{gps} 0604:{bateria:02X} 060F:{carga} 0601:{sinal} "
            f"054C:{musica:02X} 052D:{rumo} 051B:{volume_chamada:02X} 0521:{chamada}\n")


def rx03(hora, **mudancas):
    g = {"0303": "55", "0304": "55", "0305": "55", "0306": "10", "0308": "55", "0309": "04", "030A": "AAAAAAAAAA", "030B": "55555555"}
    g.update(mudancas)
    return f"{hora}  +     0ms  [detail] dash rx 0F04:55 0907:AA " + " ".join(f"{k}:{v}" for k, v in g.items()) + "\n"


def projecao_com(inicio, fim, *meio):
    return f"{inicio}  +  1000ms  ---- projeção ligada: projection ----\n" + "".join(meio) + f"{fim}  +  1000ms  ---- projeção desligada: projection ----\n"


def arquivos(texto, nome="ride-20261108-100000.log"):
    d = Path(tempfile.mkdtemp())
    escreve(d, nome, texto)
    return d, ler_registro._registros_de([d])[0]


class LinhaTest(unittest.TestCase):
    def test_a_packet_line_gives_the_time_the_direction_and_each_tlv(self):
        h, d, tlvs = detalhes.lido(tx("10:01:00.000").rstrip("\n"))
        self.assertEqual(("10:01:00.000", "tx"), (h, d))
        self.assertEqual({"0604": "A9", "054C": "10", "052D": "0000", "0610": "43"}, {k: tlvs[k] for k in ("0604", "054C", "052D", "0610")})

    def test_other_lines_are_none_and_a_repeated_tlv_keeps_the_first(self):
        self.assertIsNone(detalhes.lido("10:01:00.000  +  1ms  [wifi] connected"))
        self.assertIsNone(detalhes.lido("10:01:00.000  +  1ms  [detail] aa rx ch=0 type=5"))
        self.assertEqual("11", detalhes.lido("10:01:00.000  +  1ms  [detail] dash tx 0604:11 0604:22")[2]["0604"])

    def test_only_the_heartbeat_and_the_group_03_are_read(self):
        _, arqs = arquivos(projecao_com("10:00:00.000", "10:09:00.000", tx("10:01:00.000"), linha("10:01:01.000", "detail", "dash tx 0804:01"), rx03("10:00:05.000"),
                                        "10:00:06.000  +  1ms  [detail] dash rx 0C06:00\n"))
        ps = detalhes.ler(arqs)
        self.assertEqual(["rx", "tx"], sorted(p["direcao"] for p in ps))


class BatimentoTest(unittest.TestCase):
    def resumo(self, *linhas):
        _, arqs = arquivos(projecao_com("10:00:00.000", "10:09:00.000", *linhas))
        return detalhes.batimento(detalhes.ler(arqs))

    def campos(self, r):
        return dict(r["campos"])

    def test_a_fixed_field_is_told_as_fixed_with_what_it_means(self):
        r = self.resumo(*[tx(f"10:01:{k:02d}.000") for k in range(5)])
        self.assertEqual(5, r["pacotes"])
        c = self.campos(r)
        self.assertEqual("69 % (fixo em 5)", c["bateria do celular"])
        self.assertEqual("desligado (fixo em 5)", c["carregador"])
        self.assertEqual("ligado (fixo em 5)", c["GPS"])
        self.assertEqual("com sinal (fixo em 5)", c["sinal do celular"])
        self.assertEqual("27 °C (fixo em 5)", c["temperatura do clima"])
        self.assertEqual("0/10 (fixo em 5)", c["volume da música"])
        self.assertEqual("10/10 (fixo em 5)", c["volume da chamada"])
        self.assertEqual("0° (fixo em 5)", c["rumo"])
        self.assertEqual("32 (fixo em 5)", c["estado da chamada"])

    def test_the_music_volume_is_ten_plus_the_level(self):
        c = self.campos(self.resumo(tx("10:01:00.000", musica=0x14), tx("10:01:01.000", musica=0x14)))
        self.assertEqual("4/10 (fixo em 2)", c["volume da música"])

    def test_the_battery_that_falls_is_a_range_and_a_count_of_changes(self):
        c = self.campos(self.resumo(tx("10:01:00.000", bateria=0xA9), tx("10:01:01.000", bateria=0xA9), tx("10:01:02.000", bateria=0xA8), tx("10:01:03.000", bateria=0xA6)))
        self.assertEqual("de 69 % a 66 % (faixa 66 % a 69 %), 2 mudanças", c["bateria do celular"])

    def test_one_change_is_in_the_singular(self):
        c = self.campos(self.resumo(tx("10:01:00.000", bateria=0xA9), tx("10:01:01.000", bateria=0xA8)))
        self.assertTrue(c["bateria do celular"].endswith(", 1 mudança"), c["bateria do celular"])

    def test_a_field_that_flips_lists_each_value_with_its_count(self):
        c = self.campos(self.resumo(tx("10:01:00.000", icone="05"), tx("10:01:01.000", icone="05"), tx("10:01:02.000", icone="FF"), tx("10:01:03.000", icone="05")))
        self.assertEqual("05 ×3, FF ×1, 2 mudanças", c["ícone do clima"])

    def test_the_bearing_says_how_many_values_and_not_a_range(self):
        c = self.campos(self.resumo(tx("10:01:00.000", rumo="0010"), tx("10:01:01.000", rumo="0020"), tx("10:01:02.000", rumo="0020")))
        self.assertEqual("2 valores, 1 mudança", c["rumo"])

    def test_a_connection_without_a_heartbeat_has_no_summary(self):
        self.assertIsNone(self.resumo(rx03("10:00:05.000")))


class Grupo03Test(unittest.TestCase):
    def grupo(self, *linhas):
        _, arqs = arquivos(projecao_com("10:00:00.000", "10:09:00.000", *linhas))
        return detalhes.grupo03(detalhes.ler(arqs))

    def test_the_group_is_what_the_panel_said_at_the_opening(self):
        g = self.grupo(rx03("10:00:05.000"))
        self.assertEqual("55", g["0303"])
        self.assertEqual("AAAAAAAAAA", g["030A"])
        self.assertNotIn("0F04", g)  # not of group 03

    def test_a_later_repeat_after_the_window_is_not_the_opening_and_the_first_value_wins(self):
        g = self.grupo(rx03("10:00:05.000"), rx03("10:00:10.000", **{"0304": "AA"}), rx03("10:05:00.000", **{"0305": "AA"}))
        self.assertEqual("55", g["0304"])
        self.assertEqual("55", g["0305"])

    def test_a_key_that_comes_in_a_second_packet_inside_the_window_is_part_of_the_opening(self):
        a = "10:00:05.000  +     0ms  [detail] dash rx 0303:55 0304:55\n"
        b = "10:00:25.000  +     0ms  [detail] dash rx 0305:AA\n"
        c = "10:03:25.000  +     0ms  [detail] dash rx 0306:10\n"
        g = self.grupo(a, b, c)
        self.assertEqual({"0303": "55", "0304": "55", "0305": "AA"}, g)

    def test_no_group_03_gives_an_empty_group(self):
        self.assertEqual({}, self.grupo(tx("10:01:00.000")))


class Comparar03Test(unittest.TestCase):
    G = {"0303": "55", "0304": "55"}

    def test_the_first_connection_is_listed_and_an_equal_one_is_told_equal(self):
        t = detalhes.comparar03([("A", self.G, ""), ("B", dict(self.G), "")])
        self.assertEqual(["A: grupo 03 0303:55 0304:55", "B: grupo 03 igual ao da conexão de A"], t)

    def test_equal_after_a_write_says_the_group_did_not_change_after_it(self):
        t = detalhes.comparar03([("A", self.G, "distance KM -> MILES"), ("B", dict(self.G), "")])
        self.assertEqual("B: grupo 03 igual ao da conexão de A (a conexão anterior escreveu no painel: distance KM -> MILES); o grupo 03 não mudou depois da escrita", t[1])

    def test_a_changed_key_is_told_with_before_and_after_and_a_key_that_appears_too(self):
        t = detalhes.comparar03([("A", self.G, "distance KM -> MILES"), ("B", {"0303": "55", "0304": "AA", "0305": "10"}, "")])
        self.assertIn("MUDOU em relação à conexão de A: 0304 55→AA, 0305 -→10", t[1])
        self.assertIn("a conexão anterior escreveu no painel", t[1])

    def test_each_connection_is_compared_with_the_one_right_before_it_and_not_with_the_first(self):
        mudado = {"0303": "AA", "0304": "55"}
        t = detalhes.comparar03([("A", self.G, ""), ("B", mudado, ""), ("C", dict(mudado), "")])
        self.assertIn("B: grupo 03 MUDOU em relação à conexão de A: 0303 55→AA", t[1])
        self.assertEqual("C: grupo 03 igual ao da conexão de B", t[2])

    def test_connections_without_the_group_are_counted_in_one_line_and_skipped(self):
        t = detalhes.comparar03([("A", self.G, ""), ("X", {}, ""), ("Y", {}, ""), ("B", dict(self.G), "")])
        self.assertEqual(3, len(t))
        self.assertIn("igual ao da conexão de A", t[1])
        self.assertTrue(t[2].startswith("2 conexões sem o grupo 03"))
        self.assertTrue(detalhes.comparar03([("X", {}, "")])[0].startswith("1 conexão sem o grupo 03"))


class SecaoETelaTest(unittest.TestCase):
    def texto(self):
        return ("10:00:00.000  ==== process start: app — app 0.3.0 ====\n" +
                projecao_com("10:00:01.000", "10:09:00.000", rx03("10:00:05.000"), *[tx(f"10:01:{k:02d}.000") for k in range(3)],
                             linha("10:00:30.000", "panel", "panel settings: writing 1 block (changes: temperature CELSIUS -> FAHRENHEIT)")) +
                projecao_com("11:00:01.000", "11:09:00.000", rx03("11:00:05.000"), tx("11:01:00.000")))

    def test_each_connection_gets_its_heartbeat_and_the_group_is_compared(self):
        d, arqs = arquivos(self.texto())
        blocos = ler_registro.projecoes(ler_registro.ler([d]))
        s = detalhes.secao(arqs, blocos)
        self.assertEqual(4, len(s), s)
        self.assertIn("batimento de 3 pacotes — bateria do celular: 69 % (fixo em 3)", s[0])
        self.assertIn("batimento de 1 pacotes", s[1])
        self.assertIn("grupo 03 0303:55", s[2])
        self.assertIn("igual ao da conexão de", s[3])
        self.assertIn("temperature CELSIUS -> FAHRENHEIT", s[3])

    def test_a_log_without_packet_lines_gives_no_section(self):
        d, arqs = arquivos(projecao_com("10:00:01.000", "10:09:00.000", linha("10:00:05.000", "wifi", "connected")))
        self.assertEqual([], detalhes.secao(arqs, ler_registro.projecoes(ler_registro.ler([d]))))

    def test_the_packets_of_one_connection_are_not_given_to_another(self):
        d, arqs = arquivos(self.texto())
        blocos = ler_registro.projecoes(ler_registro.ler([d]))
        ps = detalhes.ler(arqs)
        self.assertEqual(4, len(detalhes.da_projecao(ps, blocos[0])))  # 1 rx + 3 tx
        self.assertEqual(2, len(detalhes.da_projecao(ps, blocos[1])))

    def test_a_packet_on_the_first_and_on_the_last_line_of_the_connection_belongs_to_it(self):
        d, arqs = arquivos(projecao_com("10:00:01.000", "10:09:00.000", tx("10:01:00.000")))
        bloco = ler_registro.projecoes(ler_registro.ler([d]))[0]
        na_borda = [{"arquivo": "ride-20261108-100000.log", "segundos": s, "direcao": "tx", "tlvs": {}} for s in (10 * 3600 + 1.0, 10 * 3600 + 9 * 60)]
        fora = [{"arquivo": "ride-20261108-100000.log", "segundos": s, "direcao": "tx", "tlvs": {}} for s in (10 * 3600 + 0.9, 10 * 3600 + 9 * 60 + 0.1)]
        self.assertEqual(2, len(detalhes.da_projecao(na_borda + fora, bloco)))
        self.assertEqual(0, len(detalhes.da_projecao([dict(p, arquivo="outro.log") for p in na_borda], bloco)))

    def test_the_command_line_prints_the_section(self):
        d, _ = arquivos(self.texto())
        feito = subprocess.run([sys.executable, "-I", str(AQUI / "ler_registro.py"), str(d), "--tudo"], capture_output=True, text=True, encoding="utf-8")
        self.assertIn("PACOTES DO PAINEL", feito.stdout)
        self.assertIn("batimento de 3 pacotes", feito.stdout)


if __name__ == "__main__":
    unittest.main()
