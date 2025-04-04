import ReplView from "../components/commands/ReplView";
import { DeviceProvider } from "../context/DeviceContext";
import { AuthProvider } from "../context/AuthContext";
export default {
  title: "ReplView",
};

export const Main = () => {
  return (
    <AuthProvider>
      <DeviceProvider>
        <ReplView />
      </DeviceProvider>
    </AuthProvider>
  );
};
