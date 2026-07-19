import assert from 'node:assert/strict';
import test from 'node:test';

import { normalizeObjectStorageForm } from '../src/objectStorageForm.ts';

const values = {
  configName: ' 本地 MinIO ',
  providerType: 'MINIO',
  endpoint: ' 127.0.0.1:19100 ',
  accessKey: ' minio-access ',
  secretKey: ' minio-secret ',
  useSsl: false,
  bucketName: '/ai-file-navigation/',
  basePath: '/word_clean_tts/',
  enabled: true,
  isDefault: true,
};

test('normalizes create payload without changing secret semantics', () => {
  assert.deepEqual(normalizeObjectStorageForm(values, false), {
    configName: '本地 MinIO',
    providerType: 'MINIO',
    endpoint: '127.0.0.1:19100',
    accessKey: 'minio-access',
    secretKey: 'minio-secret',
    useSsl: false,
    bucketName: 'ai-file-navigation',
    basePath: 'word_clean_tts',
    enabled: true,
    isDefault: true,
  });
});

test('keeps blank edit secret so backend preserves stored value', () => {
  assert.equal(normalizeObjectStorageForm({ ...values, secretKey: '   ' }, true).secretKey, '');
});

test('rejects a disabled default before sending the request', () => {
  assert.throws(
    () => normalizeObjectStorageForm({ ...values, enabled: false }, false),
    /默认对象存储配置必须启用/,
  );
});
