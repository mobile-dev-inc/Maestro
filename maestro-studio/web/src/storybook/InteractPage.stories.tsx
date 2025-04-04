import InteractPage from "../pages/InteractPage";
import { AuthProvider } from "../context/AuthContext";

export default {
  title: "InteractPage",
  parameters: {
    layout: "fullscreen",
  },
};

export const Main = () => {
  return (
    <AuthProvider>
      <InteractPage />
    </AuthProvider>
  );
};
