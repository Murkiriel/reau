// Decodifica um .h264 (com o .csv de extrair.py) e, por quadro, escreve em <saida>/lit.bin: a parte acesa do quadro como o REau mede
// (cópia 64x40 por amostragem bilinear do centro de cada pixel; acesa = luma >= 30), mais as contagens para os limiares LIM (15 a 60, de 5 em 5).
// Também grava <saida>/fNNNNN.ppm dos quadros pedidos (números a partir de 1, na ordem de saída do decodificador).
// Uso: dec5 arq.h264 arq.csv saida [n ...]        Liga com a libavcodec do sistema, sem cabeçalhos (as posições valem para a 60 e a 61).
// Compilar: gcc -O2 -o dec5 dec5.c /usr/lib/x86_64-linux-gnu/libavcodec.so.60 /usr/lib/x86_64-linux-gnu/libavutil.so.58 -lm   (sem os pacotes -dev: liga pelo caminho da biblioteca)
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
typedef struct AVCodec AVCodec; typedef struct AVCodecContext AVCodecContext; typedef struct AVPacket AVPacket; typedef struct AVFrame AVFrame;
extern const AVCodec* avcodec_find_decoder(int id);
extern AVCodecContext* avcodec_alloc_context3(const AVCodec*);
extern int avcodec_open2(AVCodecContext*, const AVCodec*, void*);
extern AVPacket* av_packet_alloc(void);
extern int av_new_packet(AVPacket*, int);
extern void av_packet_unref(AVPacket*);
extern AVFrame* av_frame_alloc(void);
extern int avcodec_send_packet(AVCodecContext*, AVPacket*);
extern int avcodec_receive_frame(AVCodecContext*, AVFrame*);
extern void av_log_set_level(int);
static const int LIM[] = {15, 20, 25, 30, 35, 40, 45, 50, 55, 60}; // limiares de luma
#define NT 10
#define SW 64
#define SH 40
static int want[200000]; static int nwant = 0;
static void rgb_at(uint8_t** d, int* ls, int x, int y, double* o) {
  int Y = d[0][y * ls[0] + x], U = d[1][(y / 2) * ls[1] + x / 2] - 128, V = d[2][(y / 2) * ls[2] + x / 2] - 128;
  double yy = (Y - 16) * 1.164, r = yy + 1.596 * V, g = yy - 0.392 * U - 0.813 * V, b = yy + 2.017 * U;
  o[0] = r < 0 ? 0 : r > 255 ? 255 : r; o[1] = g < 0 ? 0 : g > 255 ? 255 : g; o[2] = b < 0 ? 0 : b > 255 ? 255 : b;
}
int main(int argc, char** argv) {
  av_log_set_level(-8);
  FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long L = ftell(f); fseek(f, 0, SEEK_SET);
  uint8_t* buf = malloc(L); if (fread(buf, 1, L, f) != (size_t)L) return 2; fclose(f);
  for (int i = 4; i < argc; i++) want[nwant++] = atoi(argv[i]);
  FILE* c = fopen(argv[2], "r"); char line[256]; if (!c || !fgets(line, 256, c)) return 2;
  // .csv novo (cabeçalho bytes_in_file): a coluna já conta o código de início; antigo (bytes): soma 4 por quadro. Avisa se a soma não bate com o arquivo.
  int extra = strstr(line, "bytes_in_file") ? 0 : 4; long soma = 0; int nq = 0;
  { char l2[256]; while (fgets(l2, 256, c)) { int a, b, z, k; if (sscanf(l2, "%d,%d,%d,%d", &a, &b, &z, &k) == 4) { soma += z; nq++; } } rewind(c); if (!fgets(line, 256, c)) return 2; }
  if (soma + (long)extra * nq != L) fprintf(stderr, "AVISO: soma do .csv (%ld + %d x %d) diferente do arquivo (%ld bytes): quadros desalinhados\n", soma, extra, nq, L);
  const AVCodec* codec = avcodec_find_decoder(27);
  AVCodecContext* ctx = avcodec_alloc_context3(codec); avcodec_open2(ctx, codec, NULL);
  AVPacket* pkt = av_packet_alloc(); AVFrame* fr = av_frame_alloc();
  char path[512]; snprintf(path, 512, "%s/lit.bin", argv[3]); FILE* lb = fopen(path, "wb");
  long off = 0; int out = 0, eof = 0;
  while (1) {
    int idx, pts, sz, key;
    if (!eof) { if (!fgets(line, 256, c)) eof = 1; else sscanf(line, "%d,%d,%d,%d", &idx, &pts, &sz, &key); }
    if (!eof) { long len = extra + sz; if (off + len > L) { eof = 1; avcodec_send_packet(ctx, NULL); goto drenar; } av_new_packet(pkt, (int)len); uint8_t** pd = (uint8_t**)((char*)pkt + 24); memcpy(*pd, buf + off, len); off += len; avcodec_send_packet(ctx, pkt); av_packet_unref(pkt); }
    else avcodec_send_packet(ctx, NULL);
    drenar:
    while (avcodec_receive_frame(ctx, fr) == 0) {
      uint8_t** data = (uint8_t**)fr; int* ls = (int*)((char*)fr + 64);
      int w = *(int*)((char*)fr + 104), h = *(int*)((char*)fr + 108);
      out++;
      uint16_t cnt[NT]; memset(cnt, 0, sizeof cnt);
      for (int j = 0; j < SH; j++) for (int i = 0; i < SW; i++) {
        double fx = (i + 0.5) * w / SW - 0.5, fy = (j + 0.5) * h / SH - 0.5;
        int x0 = (int)(fx < 0 ? 0 : fx), y0 = (int)(fy < 0 ? 0 : fy); if (x0 > w - 2) x0 = w - 2; if (y0 > h - 2) y0 = h - 2;
        double ax = fx - x0; if (ax < 0) ax = 0; if (ax > 1) ax = 1; double ay = fy - y0; if (ay < 0) ay = 0; if (ay > 1) ay = 1;
        double p00[3], p10[3], p01[3], p11[3]; rgb_at(data, ls, x0, y0, p00); rgb_at(data, ls, x0 + 1, y0, p10); rgb_at(data, ls, x0, y0 + 1, p01); rgb_at(data, ls, x0 + 1, y0 + 1, p11);
        double rgb[3]; for (int k = 0; k < 3; k++) rgb[k] = (p00[k] * (1 - ax) + p10[k] * ax) * (1 - ay) + (p01[k] * (1 - ax) + p11[k] * ax) * ay;
        double luma = 0.299 * (int)rgb[0] + 0.587 * (int)rgb[1] + 0.114 * (int)rgb[2];
        for (int k = 0; k < NT; k++) if (luma >= LIM[k]) cnt[k]++;
      }
      fwrite(cnt, 2, NT, lb);
      for (int i = 0; i < nwant; i++) if (want[i] == out) {
        snprintf(path, 512, "%s/f%05d.ppm", argv[3], out); FILE* o = fopen(path, "wb"); fprintf(o, "P6\n%d %d\n255\n", w, h);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) { double p[3]; rgb_at(data, ls, x, y, p); fputc((int)p[0], o); fputc((int)p[1], o); fputc((int)p[2], o); }
        fclose(o);
      }
    }
    if (eof) break;
  }
  fprintf(stderr, "quadros decodificados: %d\n", out);
  fclose(lb); return 0;
}
