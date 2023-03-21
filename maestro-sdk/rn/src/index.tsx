import { NativeModules, Platform } from 'react-native';

const LINKING_ERROR =
  `The package 'maestro-rn-sdk' doesn't seem to be linked. Make sure: \n\n` +
  Platform.select({ ios: "- You have run 'pod install'\n", default: '' }) +
  '- You rebuilt the app after installing the package\n' +
  '- You are not using Expo Go\n';

const MaestroRnSdk = NativeModules.MaestroRnSdk
  ? NativeModules.MaestroRnSdk
  : new Proxy(
      {},
      {
        get() {
          throw new Error(LINKING_ERROR);
        },
      }
    );

export function setup(projectId: string): Promise<boolean> {
  return MaestroRnSdk.setup(projectId);
}

export function mockServerUrl(baseUrl: string): Promise<string> {
  return MaestroRnSdk.mockServerUrl(baseUrl);
}
