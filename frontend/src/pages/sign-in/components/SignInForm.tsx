import { useForm } from 'react-hook-form';
import { Link, useNavigate } from 'react-router-dom';
import { toast } from 'sonner';
import { useAuthStore } from '@/lib/stores/useAuthStore';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { cn } from '@/lib/utils';
import { useState } from 'react';
import googleIcon from '@/assets/ic_google.svg';
import kakaoIcon from '@/assets/ic_kakao.svg';

interface SignInFormData {
  email: string;
  password: string;
}

type OAuthProvider = 'google' | 'kakao';

export default function SignInForm() {
  const navigate = useNavigate();
  const [isLoading, setIsLoading] = useState(false);
  const { signIn } = useAuthStore();

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<SignInFormData>();

  const onSubmit = async (data: SignInFormData) => {
    setIsLoading(true);

    try {
      await signIn(data.email, data.password);
      toast.success('로그인에 성공했습니다');
      navigate('/contents');
    } catch (error) {
      console.error("Faile to sign in.", error);

      toast.error('로그인에 실패했습니다. 다시 시도해주세요.');
    } finally {
      setIsLoading(false);
    }
  };

  const basename = import.meta.env.VITE_PUBLIC_PATH || '';

  const handleLogin = (provider: OAuthProvider) => {

    window.location.href = `${window.location.origin}${basename}/oauth2/authorization/${provider}`
  }

  const handleGoogleLogin = () => {
    handleLogin('google');
  };

  const handleKakaoLogin = () => {
    handleLogin('kakao')
  };

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="flex w-full flex-col gap-5">
      {/* Email Field */}
      <div className="flex w-full flex-col gap-2.5">
        <Label htmlFor="email" className="px-1 text-body3-sb text-gray-500">
          이메일
        </Label>
        <div className="flex w-full flex-col gap-1.5">
          <Input
            id="email"
            type="email"
            placeholder="이메일 입력"
            className={cn(
              'h-[54px] rounded-xl border-[1.5px]',
              errors.email ? 'border-[#c93c3f]' : 'border-gray-800',
              'bg-[rgba(35,35,43,0.5)] px-5 py-3.5 text-body2-m-140 text-white placeholder:text-gray-400',
            )}
            {...register('email', {
              required: '이메일을 입력해주세요',
              pattern: {
                value: /^[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}$/i,
                message: '올바른 이메일 형식이 아닙니다',
              },
            })}
            disabled={isLoading}
          />
          {errors.email && (
            <p className="px-2 text-body3-m text-red-notification">{errors.email.message}</p>
          )}
        </div>
      </div>

      {/* Password Field */}
      <div className="flex w-full flex-col gap-2.5">
        <Label htmlFor="password" className="px-1 text-body3-sb text-gray-500">
          비밀번호
        </Label>
        <div className="flex w-full flex-col gap-1.5">
          <Input
            id="password"
            type="password"
            placeholder="비밀번호 입력"
            className={cn(
              'h-[54px] rounded-xl border-[1.5px]',
              errors.password ? 'border-[#c93c3f]' : 'border-gray-800',
              'bg-[rgba(35,35,43,0.5)] px-5 py-3.5 text-body2-m-140 text-white placeholder:text-gray-400',
            )}
            {...register('password', {
              required: '비밀번호를 입력해주세요',
            })}
            disabled={isLoading}
          />
          {errors.password && (
            <p className="px-2 text-body3-m text-red-notification">{errors.password.message}</p>
          )}
        </div>
      </div>

      {/* Submit Button */}
      <div className="w-full pt-3">
        <Button
          type="submit"
          disabled={isLoading}
          className="h-[54px] w-full rounded-xl bg-pink-500 text-body1-b text-white hover:bg-pink-600 disabled:bg-gray-800 disabled:text-gray-600"
        >
          {isLoading ? '로그인 중...' : '로그인'}
        </Button>
      </div>

      {/* Password Reset Link */}
      <div className="flex w-full justify-center text-center text-body2-m text-gray-500">
        <Link to="/reset-password" className="hover:text-gray-400">
          비밀번호를 잊으셨나요?
        </Link>
      </div>

      {/* Social Login Divider */}
      <div className="relative flex items-center gap-3 py-6">
        <div className="h-px flex-1 bg-[#212126]" />
        <span className="text-body2-m text-[#565666]">or</span>
        <div className="h-px flex-1 bg-[#212126]" />
      </div>

      {/* Social Login Buttons */}
      <div className="flex w-full flex-col gap-5">
        <button
          type="button"
          onClick={handleGoogleLogin}
          disabled={isLoading}
          className="flex h-[56px] w-full items-center justify-center gap-1 rounded-[200px] bg-[rgba(35,35,43,0.5)] text-body2-sb text-[#dfdfe2] transition-colors hover:bg-[rgba(45,45,53,0.5)] disabled:opacity-50"
        >
          <img src={googleIcon} alt="" className="size-5" />
          구글로 시작하기
        </button>

        <button
          type="button"
          onClick={handleKakaoLogin}
          disabled={isLoading}
          className="flex h-[56px] w-full items-center justify-center gap-1 rounded-[200px] bg-[rgba(35,35,43,0.5)] text-body2-sb text-[#dfdfe2] transition-colors hover:bg-[rgba(45,45,53,0.5)] disabled:opacity-50"
        >
          <img src={kakaoIcon} alt="" className="size-5" />
          카카오 시작하기
        </button>
      </div>
    </form>
  );
}
