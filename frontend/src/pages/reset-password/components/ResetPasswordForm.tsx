import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link } from 'react-router-dom';
import { toast } from 'sonner';
import { resetPassword } from '@/lib/api/auth';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/utils';
import type { ResetPasswordRequest } from '@/lib/types';
import icMailbox from '@/assets/ic_mailbox.svg';

interface ResetPasswordFormData {
  email: string;
}

interface ResetPasswordFormProps {
  onSuccess: (email: string) => void;
}

export default function ResetPasswordForm({ onSuccess }: ResetPasswordFormProps) {
  const [isLoading, setIsLoading] = useState(false);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<ResetPasswordFormData>();

  const onSubmit = async (data: ResetPasswordFormData) => {
    setIsLoading(true);

    try {
      const request: ResetPasswordRequest = {
        email: data.email,
      };

      await resetPassword(request);

      toast.success('임시 비밀번호가 이메일로 전송되었습니다');

      // Move to step 2 (verify temporary password)
      onSuccess(data.email);
    } catch (error) {
      console.error("Fail to reset password.", error);
      toast.error('비밀번호 초기화에 실패했습니다. 다시 시도해주세요.');
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="flex w-full max-w-[500px] flex-col items-center gap-10">
      {/* Icon and Title */}
      <div className="flex flex-col items-center gap-5">
        <img src={icMailbox} alt="" className="size-[70px]" />
        <h1 className="text-center text-header1-b-160 text-gray-200">
          임시 비밀번호를 받을 이메일을 입력해주세요
        </h1>
      </div>

      {/* Form */}
      <form onSubmit={handleSubmit(onSubmit)} className="flex w-full flex-col gap-[30px]">
        {/* Email Input */}
        <div className="flex w-full flex-col gap-5">
          <div className="flex w-full flex-col gap-2.5">
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

          {/* Submit Button */}
          <div className="w-full pt-1.5">
            <Button
              type="submit"
              disabled={isLoading}
              className="h-[54px] w-full rounded-xl bg-pink-600 text-body1-b text-white hover:bg-pink-500 disabled:bg-gray-800 disabled:text-gray-600"
            >
              {isLoading ? '전송 중...' : '비밀번호 초기화'}
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
