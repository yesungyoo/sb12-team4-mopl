import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useNavigate } from 'react-router-dom';
import { toast } from 'sonner';
import { updateUserPassword } from '@/lib/api/users';
import { useAuthStore } from '@/lib/stores/useAuthStore';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { cn } from '@/lib/utils';
import type { ChangePasswordRequest } from '@/lib/types';
import ilPassword from '@/assets/il_password.svg';

interface ChangePasswordFormData {
  password: string;
  passwordConfirmation: string;
}

export default function ChangePasswordForm() {
  const navigate = useNavigate();
  const [isLoading, setIsLoading] = useState(false);
  const { data: authentication, signOut } = useAuthStore();

  const {
    register,
    handleSubmit,
    formState: { errors },
    watch,
  } = useForm<ChangePasswordFormData>();

  const password = watch('password');

  const onSubmit = async (data: ChangePasswordFormData) => {
    if (!authentication) {
      toast.error('인증 정보가 없습니다. 다시 시도해주세요.');
      navigate('/sign-in');
      return;
    }

    setIsLoading(true);

    try {
      const request: ChangePasswordRequest = {
        password: data.password,
      };

      await updateUserPassword(authentication.userDto.id, request);

      toast.success('비밀번호가 변경되었습니다. 새 비밀번호로 로그인해주세요.');

      // Logout and redirect to sign-in
      await signOut();
      navigate('/sign-in');
    } catch (error) {
      console.error("Fail to change password:", error);
      toast.error('비밀번호 변경에 실패했습니다. 다시 시도해주세요.');
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="flex w-full max-w-[500px] flex-col items-center gap-10">
      {/* Icon and Title */}
      <div className="flex flex-col items-center gap-4">
        <img src={ilPassword} alt="" className="size-[70px]" />
        <h1 className="text-center text-header1-b-160 text-gray-200">새로운 비밀번호 변경</h1>
      </div>

      {/* Form */}
      <form onSubmit={handleSubmit(onSubmit)} className="flex w-full flex-col gap-[30px]">
        {/* Password Fields */}
        <div className="flex w-full flex-col gap-5">
          {/* New Password Field */}
          <div className="flex w-full flex-col gap-2.5">
            <Label htmlFor="password" className="px-1 text-body3-sb text-gray-500">
              새 비밀번호 입력
            </Label>
            <div className="flex w-full flex-col gap-1.5">
              <Input
                id="password"
                type="password"
                placeholder="●●●●●●●●"
                className={cn(
                  'h-[54px] rounded-xl border-[1.5px]',
                  errors.password ? 'border-[#c93c3f]' : 'border-gray-800',
                  'bg-[rgba(35,35,43,0.5)] px-5 py-3.5 text-body2-m-140 text-white placeholder:text-gray-400',
                )}
                {...register('password', {
                  required: '비밀번호를 입력해주세요',
                  minLength: {
                    value: 8,
                    message: '비밀번호는 최소 8자 이상이어야 합니다',
                  },
                })}
                disabled={isLoading}
              />
              {errors.password && (
                <p className="px-2 text-body3-m text-red-notification">
                  {errors.password.message}
                </p>
              )}
            </div>
          </div>

          {/* Password Confirmation Field */}
          <div className="flex w-full flex-col gap-2.5">
            <Label htmlFor="passwordConfirmation" className="px-1 text-body3-sb text-gray-500">
              새 비밀번호 확인
            </Label>
            <div className="flex w-full flex-col gap-1.5">
              <Input
                id="passwordConfirmation"
                type="password"
                placeholder="●●●●●●●●"
                className={cn(
                  'h-[54px] rounded-xl border-[1.5px]',
                  errors.passwordConfirmation ? 'border-[#c93c3f]' : 'border-gray-800',
                  'bg-[rgba(35,35,43,0.5)] px-5 py-3.5 text-body2-m-140 text-white placeholder:text-gray-400',
                )}
                {...register('passwordConfirmation', {
                  required: '비밀번호 확인을 입력해주세요',
                  validate: (value) => value === password || '비밀번호가 일치하지 않습니다',
                })}
                disabled={isLoading}
              />
              {errors.passwordConfirmation && (
                <p className="px-2 text-body3-m text-red-notification">
                  {errors.passwordConfirmation.message}
                </p>
              )}
            </div>
          </div>

          {/* Submit Button */}
          <div className="w-full pt-1.5">
            <Button
              type="submit"
              disabled={isLoading}
              className="h-[54px] w-full rounded-xl bg-pink-600 text-body1-b text-white hover:bg-pink-500 disabled:bg-gray-800 disabled:text-gray-600"
            >
              {isLoading ? '변경 중...' : '비밀번호 변경'}
            </Button>
          </div>
        </div>

        {/* Back to Login Link */}
        <div className="flex w-full items-center justify-center gap-1">
          <Link
            to="/sign-in"
            className="text-body2-m text-gray-300 underline decoration-solid underline-offset-[from-font] hover:text-gray-400"
          >
            로그인으로 돌아가기
          </Link>
        </div>
      </form>
    </div>
  );
}
