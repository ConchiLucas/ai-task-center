export interface ObjectStorageFormValues {
  configName: string;
  providerType: 'MINIO';
  endpoint: string;
  accessKey: string;
  secretKey: string;
  useSsl: boolean;
  bucketName: string;
  basePath: string;
  enabled: boolean;
  isDefault: boolean;
}

export function normalizeObjectStorageForm(
  values: ObjectStorageFormValues,
  editing: boolean,
): ObjectStorageFormValues {
  const normalized = {
    ...values,
    configName: values.configName.trim(),
    providerType: 'MINIO' as const,
    endpoint: values.endpoint.trim(),
    accessKey: values.accessKey.trim(),
    secretKey: values.secretKey.trim(),
    bucketName: cleanPath(values.bucketName),
    basePath: cleanPath(values.basePath),
    enabled: Boolean(values.enabled),
    isDefault: Boolean(values.isDefault),
  };
  if (normalized.isDefault && !normalized.enabled) {
    throw new Error('默认对象存储配置必须启用');
  }
  if (!editing && !normalized.secretKey) {
    throw new Error('请填写 Secret Key');
  }
  return normalized;
}

function cleanPath(value: string) {
  return value.trim().replace(/^\/+|\/+$/g, '');
}
