package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * O tamanho do video que o Android Auto anuncia no SPS do H.264: o decodificador avisa a tela quando ele muda. Os SPS dos
 * testes sao montados bit a bit (Exp-Golomb) com os campos que o leitor atravessa, e um e um SPS real conhecido de 1080p.
 */
class SpsParserTest {
    /** Escritor de bits do H.264: bits soltos, u(n), ue(v) e se(v). */
    private class Bits {
        private val bits = StringBuilder()
        fun bit(b: Int) = apply { bits.append(if (b != 0) '1' else '0') }
        fun u(value: Int, count: Int) = apply { for (i in count - 1 downTo 0) bit((value shr i) and 1) }
        fun ue(value: Int) = apply {
            val code = value + 1
            val length = 32 - Integer.numberOfLeadingZeros(code)
            repeat(length - 1) { bit(0) }
            u(code, length)
        }

        /** se(v): valor positivo v vira codeNum 2v-1, zero ou negativo vira -2v. */
        fun se(value: Int) = ue(if (value > 0) 2 * value - 1 else -2 * value)

        fun bytes(): ByteArray {
            bit(1) // o bit de parada do RBSP
            while (bits.length % 8 != 0) bit(0)
            return ByteArray(bits.length / 8) { bits.substring(it * 8, it * 8 + 8).toInt(2).toByte() }
        }
    }

    /** Um SPS (com o cabecalho 0x67 da NAL) de [widthMbs]x[heightMapUnits] macroblocos e o corte pedido. */
    @Suppress("LongParameterList")
    private fun sps(
        widthMbs: Int,
        heightMapUnits: Int,
        profile: Int = 66,
        frameMbsOnly: Boolean = true,
        crop: List<Int>? = null,
        chroma: Int = 1,
        scaling: (Bits) -> Unit = { it.bit(0) },
        pocType: Int = 0,
        pocOffsets: List<Int> = emptyList(),
    ): ByteArray {
        val b = Bits().u(0x67, 8).u(profile, 8).u(0, 8).u(40, 8).ue(0) // nal, perfil, restricoes, nivel, id
        if (profile in listOf(100, 110, 122, 244, 44, 83, 86, 118, 128)) {
            b.ue(chroma)
            if (chroma == 3) b.bit(0)
            b.ue(0).ue(0).bit(0) // profundidade de luma e de croma, transformacao sem perdas
            scaling(b)
        }
        b.ue(0) // log2_max_frame_num - 4
        b.ue(pocType)
        if (pocType == 0) b.ue(0)
        if (pocType == 1) { // flag, deslocamento de quadro nao de referencia e de campo, ciclo com um deslocamento por quadro
            b.bit(0).se(-3).se(2).ue(pocOffsets.size)
            pocOffsets.forEach { b.se(it) }
        }
        b.ue(1).bit(0) // quadros de referencia, lacunas
        b.ue(widthMbs - 1).ue(heightMapUnits - 1)
        b.bit(if (frameMbsOnly) 1 else 0)
        if (!frameMbsOnly) b.bit(0)
        b.bit(1) // direct_8x8
        if (crop == null) {
            b.bit(0)
        } else {
            b.bit(1)
            crop.forEach { b.ue(it) }
        }
        b.bit(0) // sem VUI
        return b.bytes()
    }

    private fun parse(sps: ByteArray) = SpsParser.parse(sps, 0, sps.size)

    @Test
    fun `um SPS baseline de 800 por 480 sai com o tamanho do video do Android Auto`() {
        assertEquals(SpsData(800, 480), parse(sps(widthMbs = 50, heightMapUnits = 30)))
    }

    @Test
    fun `um SPS baseline de 1280 por 720 sai inteiro`() {
        assertEquals(SpsData(1280, 720), parse(sps(widthMbs = 80, heightMapUnits = 45)))
    }

    @Test
    fun `a largura e a altura nao se confundem em quadro retrato`() {
        assertEquals(SpsData(480, 800), parse(sps(widthMbs = 30, heightMapUnits = 50)))
    }

    @Test
    fun `o corte tira da altura dois pixels por unidade, dos dois lados`() {
        // 1920x1088 com 4 unidades de corte embaixo: 1088 - 8 = 1080.
        assertEquals(SpsData(1920, 1080), parse(sps(widthMbs = 120, heightMapUnits = 68, crop = listOf(0, 0, 0, 4))))
        // Corte em cima e embaixo somam.
        assertEquals(SpsData(1920, 1080), parse(sps(widthMbs = 120, heightMapUnits = 68, crop = listOf(0, 0, 2, 2))))
    }

    @Test
    fun `o corte dos lados tira da largura`() {
        assertEquals(SpsData(1272, 720), parse(sps(widthMbs = 80, heightMapUnits = 45, crop = listOf(2, 2, 0, 0))))
    }

    @Test
    fun `um corte de zero nao muda nada`() {
        assertEquals(SpsData(800, 480), parse(sps(widthMbs = 50, heightMapUnits = 30, crop = listOf(0, 0, 0, 0))))
    }

    @Test
    fun `os perfis altos trazem campos a mais e o tamanho ainda sai certo`() {
        for (profile in listOf(100, 110, 122, 244, 44, 83, 86, 118, 128)) {
            assertEquals("perfil $profile", SpsData(1920, 1080), parse(sps(120, 68, profile = profile, crop = listOf(0, 0, 0, 4))))
        }
    }

    @Test
    fun `no perfil alto com croma 4 4 4 ha um bit a mais e o tamanho sai certo`() {
        assertEquals(SpsData(800, 480), parse(sps(50, 30, profile = 244, chroma = 3)))
    }

    @Test
    fun `listas de escala presentes sao atravessadas ate o tamanho`() {
        // Seis listas de 16 e duas de 64 (croma 4:2:0 tem oito); a primeira e a ultima presentes, com delta 0 (ue = 1 bit '1').
        val scaling = { b: Bits ->
            b.bit(1) // seq_scaling_matrix_present
            for (list in 0 until 8) {
                if (list == 0 || list == 7) {
                    b.bit(1)
                    repeat(if (list < 6) 16 else 64) { b.ue(0) }
                } else {
                    b.bit(0)
                }
            }
        }
        assertEquals(SpsData(800, 480), parse(sps(50, 30, profile = 100, scaling = scaling)))
    }

    @Test
    fun `o SPS pode vir depois de um prefixo, com deslocamento e tamanho`() {
        val sps = sps(50, 30)
        val inStream = byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(9, 9, 9)
        assertEquals(SpsData(800, 480), SpsParser.parse(inStream, 4, sps.size))
    }

    @Test
    fun `um SPS real de 1080p de alto perfil sai 1920 por 1080`() {
        // Um SPS de x264 (High, nivel 4.0, 1920x1088 cortado para 1080) com bytes de escape 00 00 03 depois do tamanho.
        val real = intArrayOf(
            0x67, 0x64, 0x00, 0x28, 0xAC, 0xD9, 0x40, 0x78, 0x02, 0x27, 0xE5, 0xC0, 0x44, 0x00, 0x00, 0x03, 0x00, 0x04, 0x00, 0x00,
            0x03, 0x00, 0xF0, 0x3C, 0x60, 0xC6, 0x58,
        ).map { it.toByte() }.toByteArray()
        assertEquals(SpsData(1920, 1080), parse(real))
    }

    @Test
    fun `um tamanho maior que o vetor nao lanca, devolve nulo`() {
        assertNull(SpsParser.parse(byteArrayOf(0x67, 0x42), 0, 10))
    }

    @Test
    fun `o resultado e o mesmo com o SPS no comeco ou no meio de um vetor maior`() {
        val bytes = sps(80, 45)
        val padded = ByteArray(5) + bytes
        assertNotNull(parse(bytes))
        assertEquals(parse(bytes), SpsParser.parse(padded, 5, bytes.size))
    }

    @Test
    fun `um SPS cortado antes do tamanho devolve nulo, nao um tamanho inventado`() {
        assertNull(parse(ByteArray(0)))
        assertNull(parse(sps(50, 30).copyOf(4)))
    }

    @Test
    fun `uma lista de escala que termina cedo com delta negativo nao desalinha o tamanho`() {
        // Primeira lista presente com delta se(v) = -8 (codeNum 16): proximo = 0, a lista acaba e as outras sete vem como ausentes.
        val scaling = { b: Bits ->
            b.bit(1)
            b.bit(1).ue(16)
            repeat(7) { b.bit(0) }
        }
        assertEquals(SpsData(800, 480), parse(sps(50, 30, profile = 100, scaling = scaling)))
    }

    @Test
    fun `um SPS de contagem de ordem tipo 2 (sem B-frames) sai certo`() {
        assertEquals(SpsData(800, 480), parse(sps(50, 30, pocType = 2)))
    }

    @Test
    fun `um SPS de contagem de ordem tipo 1 sai certo`() {
        assertEquals(SpsData(800, 480), parse(sps(50, 30, pocType = 1)))
    }

    @Test
    fun `um SPS de contagem de ordem tipo 1 com ciclo de dois deslocamentos sai certo`() {
        // Deslocamentos com sinal: o positivo (codeNum impar) e o negativo (par) ocupam tamanhos diferentes e desalinham se lidos sem sinal.
        assertEquals(SpsData(800, 480), parse(sps(50, 30, pocType = 1, pocOffsets = listOf(2, -2))))
        assertEquals(SpsData(1280, 720), parse(sps(80, 45, pocType = 1, pocOffsets = listOf(-7, 100, 0, 5))))
    }

    @Test
    fun `uma lista de escala com deltas positivos e negativos que nao chega a zero le todos os valores`() {
        // Lista 4x4 presente: deltas +3, -2, +1 e zeros ate 16 valores (last = 8, 11, 9, 10 ...); as outras sete ausentes.
        val scaling = { b: Bits ->
            b.bit(1)
            b.bit(1).se(3).se(-2).se(1)
            repeat(13) { b.se(0) }
            repeat(7) { b.bit(0) }
        }
        assertEquals(SpsData(800, 480), parse(sps(50, 30, profile = 100, scaling = scaling)))
    }

    @Test
    fun `uma lista de escala 8x8 que acaba no meio com delta negativo para os valores seguintes`() {
        // Lista 8x8 (indice 6) com delta -8 depois de +4: last 8 -> 12 -> 4, depois delta -4 chega a 0 no terceiro valor e a lista acaba.
        val scaling: (Bits) -> Unit = { b ->
            b.bit(1)
            repeat(6) { b.bit(0) }
            b.bit(1).se(4).se(-8).se(-4)
            b.bit(0)
        }
        assertEquals(SpsData(800, 480), parse(sps(50, 30, profile = 100, scaling = scaling)))
    }

    @Test
    fun `um SPS de croma 4 4 4 le as doze listas de escala`() {
        val scaling = { b: Bits ->
            b.bit(1)
            repeat(12) { b.bit(1).se(-8) } // cada lista presente acaba no primeiro valor (matriz padrao)
        }
        assertEquals(SpsData(800, 480), parse(sps(50, 30, profile = 244, chroma = 3, scaling = scaling)))
    }

    @Test
    fun `um SPS cortado em qualquer ponto nunca inventa um tamanho`() {
        val full = sps(50, 30)
        for (cut in 1 until full.size - 2) {
            val result = SpsParser.parse(full, 0, cut)
            // Cortado antes do fim do tamanho nunca pode devolver um tamanho; depois dele (so no corte) pode devolver o certo ou nulo.
            if (result != null) assertEquals(SpsData(800, 480), result)
        }
        assertNull(SpsParser.parse(full, 0, 5))
    }
}
