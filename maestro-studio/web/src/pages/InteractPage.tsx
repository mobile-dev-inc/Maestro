import { DeviceProvider } from "../context/DeviceContext";
import InteractPageLayout from "../components/interact/InteractPageLayout";

export default function InteractPage() {
  return (
    <DeviceProvider>
      <InteractPageLayout />
    </DeviceProvider>
  );
}
