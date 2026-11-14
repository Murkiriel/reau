"""The two ciphers of the dash's key exchange, with the standard library alone: RSA with PKCS#1 v1.5 padding (the dash's key pair, made
when the tool starts and kept only in memory) and AES-256 in CBC mode with PKCS#7 padding (what goes under the session key).

Plain Python, for a bench: a key exchange and a few short values a session. It makes no attempt to run in constant time and is not for
anything but this tool. A private key never leaves the process: nothing here writes one, and [RsaKey] does not print its own.
"""
import math
import secrets

RSA_BITS = 1024
PUBLIC_EXPONENT = 65537
BLOCK = 16
AES_KEY_BYTES = 32

_SMALL_PRIMES = [p for p in range(3, 2000, 2) if all(p % q for q in range(3, int(p ** 0.5) + 1, 2))]
_MILLER_RABIN_ROUNDS = 40
_MIN_PADDING = 8


# ── RSA ──────────────────────────────────────────────────────────────────────────────────────────────────────────────

class RsaKey:
    """An RSA key pair: the modulus [n], the public exponent [e] and the private one."""

    def __init__(self, n, e, d):
        self.n = n
        self.e = e
        self._d = d

    @property
    def size(self):
        """The modulus in bytes, which is the size of a ciphertext."""
        return (self.n.bit_length() + 7) // 8

    def modulus_bytes(self):
        """The modulus as the dash sends it: big-endian, with no leading zero."""
        return self.n.to_bytes(self.size, "big")

    def exponent_bytes(self):
        return self.e.to_bytes((self.e.bit_length() + 7) // 8, "big")

    def decrypt(self, ciphertext):
        """The message of a PKCS#1 v1.5 [ciphertext] made for this key; ValueError for anything that is not one."""
        if len(ciphertext) != self.size:
            raise ValueError("the ciphertext has %d bytes, the key takes %d" % (len(ciphertext), self.size))
        c = int.from_bytes(ciphertext, "big")
        if c >= self.n:
            raise ValueError("the ciphertext is not below the modulus")
        return _unpad(pow(c, self._d, self.n).to_bytes(self.size, "big"))

    def __repr__(self):
        return "RsaKey(%d bits, private part not shown)" % self.n.bit_length()


def is_probable_prime(n, rand=None):
    if n < 2:
        return False
    if n in (2, 3):
        return True
    if n % 2 == 0:
        return False
    for p in _SMALL_PRIMES:
        if n == p:
            return True
        if n % p == 0:
            return False
    rand = rand or secrets.SystemRandom()
    d, r = n - 1, 0
    while d % 2 == 0:
        d //= 2
        r += 1
    for _ in range(_MILLER_RABIN_ROUNDS):
        x = pow(rand.randrange(2, n - 1), d, n)
        if x in (1, n - 1):
            continue
        for _ in range(r - 1):
            x = pow(x, 2, n)
            if x == n - 1:
                break
        else:
            return False
    return True


def _prime(bits, e, rand):
    while True:
        # The two top bits set: the product of two such primes has exactly twice the bits.
        candidate = rand.getrandbits(bits) | (1 << (bits - 1)) | (1 << (bits - 2)) | 1
        if math.gcd(candidate - 1, e) == 1 and is_probable_prime(candidate, rand):
            return candidate


def key_from_primes(p, q, e=PUBLIC_EXPONENT):
    """The key pair of the primes [p] and [q]."""
    lam = (p - 1) * (q - 1) // math.gcd(p - 1, q - 1)
    return RsaKey(p * q, e, pow(e, -1, lam))


def generate_key(bits=RSA_BITS, e=PUBLIC_EXPONENT, rand=None):
    """A new key pair of [bits] bits. [rand] (getrandbits, randrange) is the system's secure generator unless a test gives its own."""
    rand = rand or secrets.SystemRandom()
    while True:
        p = _prime(bits // 2, e, rand)
        q = _prime(bits - bits // 2, e, rand)
        if p != q:
            return key_from_primes(p, q, e)


def rsa_encrypt(message, n, e, rand=None):
    """[message] under the public key ([n], [e]) with PKCS#1 v1.5 padding: `00 02`, random bytes that are not zero, `00`, the message."""
    size = (n.bit_length() + 7) // 8
    if len(message) > size - 3 - _MIN_PADDING:
        raise ValueError("the message has %d bytes, a %d-byte key takes %d" % (len(message), size, size - 3 - _MIN_PADDING))
    rand = rand or secrets.SystemRandom()
    padding = bytes(rand.randrange(1, 256) for _ in range(size - 3 - len(message)))
    block = b"\x00\x02" + padding + b"\x00" + message
    return pow(int.from_bytes(block, "big"), e, n).to_bytes(size, "big")


def _unpad(block):
    if block[0:2] != b"\x00\x02":
        raise ValueError("not a PKCS#1 v1.5 block (was it made for another key?)")
    end = block.find(b"\x00", 2)
    if end < 2 + _MIN_PADDING:
        raise ValueError("a PKCS#1 v1.5 block with too little padding")
    return block[end + 1:]


# ── AES-256 ──────────────────────────────────────────────────────────────────────────────────────────────────────────

def _rotl8(x, n):
    return ((x << n) | (x >> (8 - n))) & 0xFF


def _make_sbox():
    """The AES substitution table, worked out from its definition: the inverse in GF(2^8), then the affine map."""
    sbox = [0] * 256
    p = q = 1
    while True:
        p = (p ^ (p << 1) ^ (0x1B if p & 0x80 else 0)) & 0xFF  # p times 3
        q ^= q << 1  # q divided by 3
        q ^= q << 2
        q ^= q << 4
        q &= 0xFF
        if q & 0x80:
            q ^= 0x09
        sbox[p] = q ^ _rotl8(q, 1) ^ _rotl8(q, 2) ^ _rotl8(q, 3) ^ _rotl8(q, 4) ^ 0x63
        if p == 1:
            break
    sbox[0] = 0x63
    return sbox


_SBOX = _make_sbox()
_INV_SBOX = [0] * 256
for _i, _s in enumerate(_SBOX):
    _INV_SBOX[_s] = _i


def _mul(a, b):
    """The product of two bytes in GF(2^8)."""
    out = 0
    while b:
        if b & 1:
            out ^= a
        a = ((a << 1) ^ 0x1B) & 0xFF if a & 0x80 else a << 1
        b >>= 1
    return out


_MUL = {k: [_mul(x, k) for x in range(256)] for k in (2, 3, 9, 11, 13, 14)}
_ROUNDS = 14
_KEY_WORDS = 8


def _expand(key):
    """The round keys of a 256-bit [key]: fifteen of 16 bytes."""
    if len(key) != AES_KEY_BYTES:
        raise ValueError("an AES-256 key has 32 bytes, this one has %d" % len(key))
    words = [list(key[i:i + 4]) for i in range(0, AES_KEY_BYTES, 4)]
    rcon = 1
    for i in range(_KEY_WORDS, 4 * (_ROUNDS + 1)):
        w = list(words[i - 1])
        if i % _KEY_WORDS == 0:
            w = [_SBOX[b] for b in w[1:] + w[:1]]
            w[0] ^= rcon
            rcon = _mul(rcon, 2)
        elif i % _KEY_WORDS == 4:
            w = [_SBOX[b] for b in w]
        words.append([a ^ b for a, b in zip(words[i - _KEY_WORDS], w)])
    return [sum(words[r * 4:r * 4 + 4], []) for r in range(_ROUNDS + 1)]


def _add(state, round_key):
    return [a ^ b for a, b in zip(state, round_key)]


# The state is the block's 16 bytes in order, four to a column: byte r + 4c is row r of column c. Row r turns r places.
_SHIFT = [(i + 4 * (i % 4)) % 16 for i in range(16)]
_UNSHIFT = [(i - 4 * (i % 4)) % 16 for i in range(16)]


def _mix(state, m):
    out = []
    for c in range(0, 16, 4):
        col = state[c:c + 4]
        out += [m[0][col[i % 4]] ^ m[1][col[(i + 1) % 4]] ^ m[2][col[(i + 2) % 4]] ^ m[3][col[(i + 3) % 4]] for i in range(4)]
    return out


_IDENT = list(range(256))
_FORWARD = (_MUL[2], _MUL[3], _IDENT, _IDENT)
_BACKWARD = (_MUL[14], _MUL[11], _MUL[13], _MUL[9])


def _encrypt_block(block, keys):
    state = _add(list(block), keys[0])
    for r in range(1, _ROUNDS + 1):
        state = [_SBOX[state[i]] for i in _SHIFT]
        if r < _ROUNDS:
            state = _mix(state, _FORWARD)
        state = _add(state, keys[r])
    return bytes(state)


def _decrypt_block(block, keys):
    state = _add(list(block), keys[_ROUNDS])
    for r in range(_ROUNDS - 1, -1, -1):
        state = [_INV_SBOX[state[i]] for i in _UNSHIFT]
        state = _add(state, keys[r])
        if r > 0:
            state = _mix(state, _BACKWARD)
    return bytes(state)


def aes_encrypt_block(key, block):
    """One 16-byte block under a 256-bit key (the raw cipher: the tests check it against the standard's own example)."""
    return _encrypt_block(block, _expand(key))


def cbc_encrypt(key, iv, plain):
    """[plain] under [key] in CBC mode from [iv], padded as PKCS#7 (what Java calls PKCS5Padding). The IV is not part of what comes back."""
    if len(iv) != BLOCK:
        raise ValueError("the IV has 16 bytes")
    keys = _expand(key)
    pad = BLOCK - len(plain) % BLOCK
    data = bytes(plain) + bytes([pad]) * pad
    out = bytearray()
    last = iv
    for at in range(0, len(data), BLOCK):
        last = _encrypt_block(bytes(a ^ b for a, b in zip(data[at:at + BLOCK], last)), keys)
        out += last
    return bytes(out)


def cbc_decrypt(key, iv, ciphertext):
    """The reverse of [cbc_encrypt]; ValueError when the size or the padding is not right (a wrong key, almost always)."""
    if len(iv) != BLOCK:
        raise ValueError("the IV has 16 bytes")
    if not ciphertext or len(ciphertext) % BLOCK:
        raise ValueError("the ciphertext is not a whole number of 16-byte blocks")
    keys = _expand(key)
    out = bytearray()
    last = iv
    for at in range(0, len(ciphertext), BLOCK):
        block = ciphertext[at:at + BLOCK]
        out += bytes(a ^ b for a, b in zip(_decrypt_block(block, keys), last))
        last = block
    pad = out[-1]
    if not 1 <= pad <= BLOCK or out[-pad:] != bytes([pad]) * pad:
        raise ValueError("the padding is not right (a wrong key?)")
    return bytes(out[:-pad])
