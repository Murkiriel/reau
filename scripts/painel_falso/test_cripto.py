"""Tests for cripto.py: the two ciphers of the key exchange against known answers, and what each refuses.

The AES answers are the standards' own examples (FIPS 197, appendix C.3; NIST SP 800-38A, F.2.5). The RSA ones are worked out here from
small numbers and from a key pair made at each run from a seeded generator: no key is written in this file.

Run from this folder:  python3 -B -m unittest discover -p "test_*.py"
"""
import os
import random
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import cripto  # noqa: E402


class AllOnes:
    """A generator whose every padding byte is FF, so a padded block is known beforehand."""

    def randrange(self, low, high):
        return high - 1


class AesTest(unittest.TestCase):
    def test_one_block_is_the_standards_example(self):
        key = bytes(range(32))
        block = bytes.fromhex("00112233445566778899aabbccddeeff")
        self.assertEqual("8ea2b7ca516745bfeafc49904b496089", cripto.aes_encrypt_block(key, block).hex())

    def test_cbc_is_the_standards_example(self):
        key = bytes.fromhex("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4")
        iv = bytes(range(16))
        plain = bytes.fromhex("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51"
                              "30c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710")
        expected = ("f58c4c04d6e5f1ba779eabfb5f7bfbd69cfc4e967edb808d679f777bc6702c7d"
                    "39f23369a9d9bacfa530e26304231461b2eb05e2c39be9fcda6c19078c6a9d1b")
        ciphertext = cripto.cbc_encrypt(key, iv, plain)
        # Four whole blocks, then the block of padding a whole number of blocks always gets.
        self.assertEqual(expected, ciphertext[:64].hex())
        self.assertEqual(80, len(ciphertext))
        self.assertEqual(plain, cripto.cbc_decrypt(key, iv, ciphertext))

    def test_every_length_comes_back_and_is_padded_to_the_next_block(self):
        key, iv = bytes(range(32, 64)), bytes(range(16, 32))
        for size in (0, 1, 15, 16, 17, 31, 32, 100):
            plain = bytes((7 * i) & 0xFF for i in range(size))
            ciphertext = cripto.cbc_encrypt(key, iv, plain)
            self.assertEqual((size // 16 + 1) * 16, len(ciphertext), size)
            self.assertEqual(plain, cripto.cbc_decrypt(key, iv, ciphertext), size)

    def test_the_iv_changes_the_first_block_only_through_the_chain(self):
        key = bytes(range(32))
        one = cripto.cbc_encrypt(key, bytes(16), b"the same sixteen")
        other = cripto.cbc_encrypt(key, bytes([1]) + bytes(15), b"the same sixteen")
        self.assertNotEqual(one[:16], other[:16])

    def test_a_wrong_key_or_a_cut_ciphertext_is_refused(self):
        key, iv = bytes(range(32)), bytes(16)
        ciphertext = cripto.cbc_encrypt(key, iv, b"caller")
        with self.assertRaises(ValueError):
            cripto.cbc_decrypt(bytes(32), iv, ciphertext)
        with self.assertRaises(ValueError):
            cripto.cbc_decrypt(key, iv, ciphertext[:-1])
        with self.assertRaises(ValueError):
            cripto.cbc_decrypt(key, iv, b"")
        with self.assertRaises(ValueError):
            cripto.cbc_decrypt(key, iv[:8], ciphertext)
        with self.assertRaises(ValueError):
            cripto.cbc_encrypt(key[:16], iv, b"caller")

    def test_a_padding_byte_of_zero_or_past_a_block_is_refused(self):
        key, iv = bytes(range(32)), bytes(16)
        keys = cripto._expand(key)
        for last in (0, 17):
            block = cripto._encrypt_block(bytes(15) + bytes([last]), keys)
            with self.assertRaises(ValueError):
                cripto.cbc_decrypt(key, iv, block)


class RsaTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.key = cripto.generate_key(rand=random.Random(20261113))

    def test_the_schoolbook_numbers(self):
        key = cripto.key_from_primes(61, 53, 17)
        self.assertEqual(3233, key.n)
        self.assertEqual(2790, pow(65, key.e, key.n))
        self.assertEqual(65, pow(2790, key._d, key.n))

    def test_a_new_key_has_1024_bits_and_the_usual_exponent(self):
        self.assertEqual(1024, self.key.n.bit_length())
        self.assertEqual(65537, self.key.e)
        self.assertEqual(128, len(self.key.modulus_bytes()))
        self.assertGreaterEqual(self.key.modulus_bytes()[0], 0x80)
        self.assertEqual(bytes([1, 0, 1]), self.key.exponent_bytes())

    def test_two_keys_are_not_the_same(self):
        self.assertNotEqual(cripto.generate_key().n, cripto.generate_key().n)

    def test_the_padded_block_is_the_one_the_standard_says(self):
        message = b"RE_BENCH_0001" + bytes(range(32))
        block = b"\x00\x02" + b"\xff" * (128 - 3 - len(message)) + b"\x00" + message
        expected = pow(int.from_bytes(block, "big"), self.key.e, self.key.n).to_bytes(128, "big")
        self.assertEqual(expected, cripto.rsa_encrypt(message, self.key.n, self.key.e, rand=AllOnes()))
        self.assertEqual(message, self.key.decrypt(expected))

    def test_what_one_side_encrypts_the_other_reads(self):
        for size in (0, 1, 32, 45, 117):
            message = bytes((3 * i + size) & 0xFF for i in range(size))
            ciphertext = cripto.rsa_encrypt(message, self.key.n, self.key.e)
            self.assertEqual(128, len(ciphertext))
            self.assertEqual(message, self.key.decrypt(ciphertext))

    def test_the_padding_is_random_and_has_no_zero(self):
        message = b"same"
        self.assertNotEqual(cripto.rsa_encrypt(message, self.key.n, self.key.e), cripto.rsa_encrypt(message, self.key.n, self.key.e))
        for _ in range(20):
            block = pow(int.from_bytes(cripto.rsa_encrypt(message, self.key.n, self.key.e), "big"), self.key._d, self.key.n).to_bytes(128, "big")
            self.assertEqual(b"\x00\x02", block[:2])
            self.assertNotIn(0, block[2:-len(message) - 1])
            self.assertEqual(b"\x00" + message, block[-len(message) - 1:])

    def test_a_message_too_long_for_the_key_is_refused(self):
        with self.assertRaises(ValueError):
            cripto.rsa_encrypt(bytes(118), self.key.n, self.key.e)

    def test_what_is_not_a_ciphertext_of_this_key_is_refused(self):
        other = cripto.generate_key(rand=random.Random(7))
        with self.assertRaises(ValueError):
            self.key.decrypt(bytes(127))
        with self.assertRaises(ValueError):
            self.key.decrypt(b"\xff" * 128)
        refused = 0
        for i in range(8):
            try:
                self.key.decrypt(cripto.rsa_encrypt(bytes([i]) * 40, other.n, other.e))
            except ValueError:
                refused += 1
        self.assertGreaterEqual(refused, 7)

    def test_a_block_with_too_little_padding_is_refused(self):
        def raw(block):
            return pow(int.from_bytes(block, "big"), self.key.e, self.key.n).to_bytes(128, "big")
        with self.assertRaises(ValueError):
            self.key.decrypt(raw(b"\x00\x02" + b"\xff" * 7 + b"\x00" + bytes(118)))
        self.assertEqual(bytes(117), self.key.decrypt(raw(b"\x00\x02" + b"\xff" * 8 + b"\x00" + bytes(117))))
        with self.assertRaises(ValueError):
            self.key.decrypt(raw(b"\x00\x01" + b"\xff" * 100 + b"\x00" + bytes(25)))
        with self.assertRaises(ValueError):
            self.key.decrypt(raw(b"\x00\x02" + b"\xff" * 126))

    def test_the_private_part_is_not_printed(self):
        self.assertNotIn(str(self.key._d), repr(self.key) + str(self.key))

    def test_primes_and_what_only_looks_like_one(self):
        for prime in (2, 3, 5, 1999, 2003, 104729, 2 ** 127 - 1):
            self.assertTrue(cripto.is_probable_prime(prime), prime)
        for composite in (0, 1, 4, 561, 1999 * 2003, 2003 * 2011, (2 ** 61 - 1) * (2 ** 31 - 1), 3215031751):
            self.assertFalse(cripto.is_probable_prime(composite), composite)


if __name__ == "__main__":
    unittest.main()
