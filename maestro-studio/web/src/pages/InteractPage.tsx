import { DeviceProvider } from "../context/DeviceContext";
import InteractPageLayout from "../components/interact/InteractPageLayout";
import { ReplProvider } from '../context/ReplContext';

export default function InteractPage() {
  return (
    <DeviceProvider>
      <ReplProvider>
        <InteractPageLayout />
      </ReplProvider>
    </DeviceProvider>
  );
}
