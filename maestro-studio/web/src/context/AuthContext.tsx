import React, { ReactNode, createContext, useContext } from "react";
import { API } from "../api/api";
import _ from "lodash";

interface AuthProviderProps {
  children: ReactNode;
}

interface AuthState {
  authToken: string | null | undefined;
  openAiToken: string | null | undefined;
  refetchAuth: () => void;
  deleteOpenAiToken: () => void;
}

const AuthContext = createContext<AuthState | undefined>(undefined);

export const AuthProvider: React.FC<AuthProviderProps> = ({ children }) => {
  const { data, mutate: refetchAuth } = API.useAuth();

  const deleteOpenAiToken = async () => {
    await API.deleteOpenAiToken();
    refetchAuth();
  };

  return (
    <AuthContext.Provider
      value={{
        authToken: _.get(data, "authToken", undefined),
        openAiToken: _.get(data, "openAiToken", undefined),
        refetchAuth,
        deleteOpenAiToken,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = (): AuthState => {
  const context = useContext(AuthContext);
  if (context === undefined) {
    throw new Error("useAuth must be used within an AuthProvider");
  }
  return context;
};
