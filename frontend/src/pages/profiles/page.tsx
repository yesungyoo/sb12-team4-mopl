import {Navigate} from 'react-router-dom';
import {useAuthStore} from "@/lib/stores/useAuthStore.ts";

export default function ProfileRoutePage() {
  const { data: authentication } = useAuthStore();

  return <Navigate to={`/profiles/${authentication?.userDto.id}`} replace />;
}
