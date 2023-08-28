import ReplView from "../components/commands/ReplView";
import { DeviceProvider } from "../context/DeviceContext";

export default {
  title: "ReplView",
};

export const Main = () => {
  return (
    <DeviceProvider>
      <ReplView />
    </DeviceProvider>
  );
};
