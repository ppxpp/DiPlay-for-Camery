import { describe, expect, it } from 'vitest';
import { verifyPassword } from '../functions/_shared/admin-auth';

const PASSWORD = 'correct horse battery staple';
const HASH = '000102030405060708090a0b0c0d0e0f:49d49c25f597846209f0d92e7770ab64e1c75e94b4ce6c509265ee67175d2a1e:100000';

describe('verifyPassword', () => {
  it('accepts hashes within the Cloudflare PBKDF2 limit', async () => {
    await expect(verifyPassword(PASSWORD, HASH)).resolves.toBe(true);
    await expect(verifyPassword('wrong password', HASH)).resolves.toBe(false);
  });

  it('rejects unsupported iteration counts without throwing', async () => {
    await expect(verifyPassword(PASSWORD, HASH.replace(':100000', ':210000'))).resolves.toBe(false);
  });
});
