import {NativeModules} from 'react-native';

/**
 * Bridge to the native FixtureEmitter module, which writes MAESTRO_FIXTURE-tagged logcat JSON that
 * the conformance harness's out-of-band oracle reads. Same contract as the native/compose fixtures.
 */
const {FixtureEmitter} = NativeModules as {
  FixtureEmitter: {
    emit(event: string, payload: Record<string, unknown>): void;
    seedState(): void;
  };
};

export function emit(event: string, payload: Record<string, unknown> = {}): void {
  FixtureEmitter.emit(event, payload);
}

export function seedState(): void {
  FixtureEmitter.seedState();
}
