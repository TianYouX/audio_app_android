import time
import requests
import websockets
import asyncio
import json
import base64
import logging
import uuid
import threading
import queue
import pyaudio
import io
import wave
import tkinter as tk
import struct
import math
from PIL import Image, ImageTk, ImageSequence
from collections import deque


# -------------------- Logging 配置 --------------------
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(levelname)s - %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S"
)

# -------------------- 服务端及鉴权配置 --------------------
# IP_PORT = "103.160.66.23:8504"
IP_PORT = "ai-bot.universeaction.com"
API_BASE_URL = f"http://{IP_PORT}"
AUTHORIZATION_TOKEN = "147258369"


def generate_event_id(prefix="evt"):
    """生成唯一 event_id"""
    return f"{prefix}_{uuid.uuid4().hex}"


def create_session():
    """通过 HTTP 接口创建会话"""
    url = f"{API_BASE_URL}/v1/realtime/sessions"
    headers = {
        "Authorization": f"Bearer {AUTHORIZATION_TOKEN}",
        "Content-Type": "application/json"
    }
    payload = {
        "model": "UNAL_ai_voice",
        "modalities": ["audio", "text"],
        # "instructions": "普通对骂"
        "instructions": "普通聊天"
        # "instructions": "夫妻模式"
    }
    response = requests.post(url, headers=headers, json=payload)
    if response.status_code == 200:
        session_data = response.json()
        logging.info(f"会话创建成功: {session_data}")
        return session_data
    else:
        logging.error(f"会话创建失败: {response.status_code} {response.text}")
        return None


# -------------------- 音频参数设置 --------------------
# 录音（输入）：16k、单声道、16位 PCM
RECORD_RATE = 16000
RECORD_CHANNELS = 1
RECORD_FORMAT = pyaudio.paInt16
FRAMES_PER_BUFFER = 2048

# 播放（输出）：24k、单声道、16位 PCM
PLAYBACK_RATE = 24000
PLAYBACK_CHANNELS = 1
PLAYBACK_FORMAT = pyaudio.paInt16

# -------------------- 全局变量 --------------------
ws_connection = None
ws_loop = None
audio_queue = queue.Queue()
recording_event = threading.Event()
recording_thread = None
audio_response_buffer = bytearray()
response_file_counter = 0
send_file_counter = 0  # 新增：记录发送音频文件的计数器
# 当设置为 True 时暂停录音累积（等待回复音频播放完成）
recording_paused = threading.Event()


# -------------------- 音频播放线程 --------------------
def play_audio():
    """
    播放线程：
      从 audio_queue 队列中获取数据，通过 PyAudio 以 24k 采样播放音频。
    """
    p = pyaudio.PyAudio()
    stream = p.open(format=PLAYBACK_FORMAT,
                    channels=PLAYBACK_CHANNELS,
                    rate=PLAYBACK_RATE,
                    output=True,
                    frames_per_buffer=1024)
    logging.info("音频播放线程启动")
    try:
        while True:
            data = audio_queue.get()  # 阻塞等待数据
            if data is None:
                break  # 收到结束信号时退出
            stream.write(data)
    except Exception as e:
        logging.error("播放音频时出错: %s", e)
    finally:
        stream.stop_stream()
        stream.close()
        p.terminate()
        logging.info("音频播放线程结束")


def generate_wav_data(pcm_data, channels, sample_rate, sample_width=2):
    """
    将 PCM 数据转换为带 WAV 文件头的完整 WAV 数据
    """
    buffer = io.BytesIO()
    with wave.open(buffer, 'wb') as wf:
        wf.setnchannels(channels)
        wf.setsampwidth(sample_width)
        wf.setframerate(sample_rate)
        wf.writeframes(pcm_data)
    return buffer.getvalue()


async def wait_for_playback_completion():
    """
    异步监控播放队列，等待直到队列为空，并额外等待一小段时间确保播放完全结束
    """
    while not audio_queue.empty():
        await asyncio.sleep(0.1)
    await asyncio.sleep(0.2)


# -------------------- WebSocket 客户端 --------------------
async def websocket_client(session_id):
    """
    WebSocket 客户端：
      - 建立连接后保存 ws_connection 与 ws_loop；
      - 接收到 "response.audio.delta" 消息后，将 base64 解码后的 PCM 数据放入播放队列，并收集到回复缓存；
      - 收到 "response.audio.done" 或 "response.audio.cancelled" 后，等待音频播放完成，再恢复录音累积；
      - 收到 "input_audio_buffer.cleared" 时记录成功。
    """
    global ws_connection, ws_loop, audio_response_buffer, response_file_counter
    ws_url = f"ws://{IP_PORT}/v1/realtime/sessions/{session_id}"
    try:
        async with websockets.connect(ws_url) as websocket:
            ws_connection = websocket
            ws_loop = asyncio.get_running_loop()
            logging.info(f"WebSocket 连接成功: {ws_url}")
            while True:
                response = await websocket.recv()
                response_data = json.loads(response)
                msg_type = response_data.get("type")
                logging.info("收到消息类型: %s", msg_type)
                if msg_type == "error":
                    logging.error(response_data.get("error"))
                elif msg_type == "conversation.item.created":
                    logging.info("收到语音转写完成: %s", response_data)
                elif msg_type == "response.audio.delta":
                    delta_b64 = response_data.get("delta")
                    raw_pcm = base64.b64decode(delta_b64)
                    audio_queue.put(raw_pcm)
                    audio_response_buffer.extend(raw_pcm)
                    logging.info("收到 audio delta")
                elif msg_type == "response.audio_transcript.delta":
                    logging.info("收到语音转写文本: %s", response_data.get("delta"))
                elif msg_type == "response.text.delta":
                    logging.info("收到回复的文本: %s", response_data.get("delta"))
                elif msg_type == "response.audio.done":
                    logging.info("语音回复结束标志: response.audio.done")
                    if len(audio_response_buffer) > 0:
                        wav_data = generate_wav_data(audio_response_buffer,
                                                     channels=PLAYBACK_CHANNELS,
                                                     sample_rate=PLAYBACK_RATE)
                        filename = f"{response_file_counter}.wav"
                        with open(filename, "wb") as f:
                            f.write(wav_data)
                        logging.info("保存回复音频为 %s", filename)
                        response_file_counter += 1
                        audio_response_buffer.clear()
                    await wait_for_playback_completion()
                    time.sleep(0.5)
                    recording_paused.clear()
                elif msg_type == "response.audio.cancelled":
                    logging.info("语音回复取消, 清空当前音频数据")
                    audio_response_buffer.clear()
                    await wait_for_playback_completion()
                    recording_paused.clear()
                elif msg_type == "input_audio_buffer.cleared":
                    logging.info("撤销或清除音频缓存成功")
    except Exception as e:
        logging.error("WebSocket 连接异常: %s", e)
        ws_connection = None
        ws_loop = None


def start_websocket_client(session_id):
    """在独立线程中启动 WebSocket 客户端的 asyncio 事件循环"""
    asyncio.run(websocket_client(session_id))


# -------------------- 录音与发送功能 --------------------
def send_audio_segment(pcm_segment):
    """
    将 PCM16 数据封装为带 WAV 头的完整 WAV，再 Base64 编码后通过 WebSocket 发送，
    并保存本地 WAV 文件以便调试。
    """
    global ws_connection, ws_loop, send_file_counter
    if ws_connection is None or ws_loop is None:
        logging.error("WebSocket 连接未就绪，无法发送音频段")
        return

    # —— 必要修改开始 ——
    # 1. 先把 PCM16 数据封装为带 WAV 头的完整 WAV 二进制
    wav_bytes = generate_wav_data(pcm_segment,
                                  channels=RECORD_CHANNELS,
                                  sample_rate=RECORD_RATE)
    # 2. 本地保存该 WAV 文件，用于调试
    filename = f"{send_file_counter}_send.wav"
    with open(filename, "wb") as f:
        f.write(wav_bytes)
    logging.info("保存发送的 WAV 为 %s", filename)
    send_file_counter += 1

    # 3. 对整个 WAV 二进制做 Base64 编码
    wav_b64 = base64.b64encode(wav_bytes).decode('utf-8')
    message = {
        "type": "input_audio_buffer.append",
        "event_id": generate_event_id(),
        "audio": wav_b64
    }
    future = asyncio.run_coroutine_threadsafe(ws_connection.send(json.dumps(message)), ws_loop)
    try:
        future.result()
        logging.info("发送带 WAV 头的音频段, 字节数: %d", len(wav_bytes))
    except Exception as e:
        logging.error("发送音频段出错: %s", e)
    # —— 必要修改结束 ——


def send_commit():
    """
    发送 commit 消息，表示当前语音输入结束
    """
    global ws_connection, ws_loop
    if ws_connection is None or ws_loop is None:
        logging.error("WebSocket 连接未就绪，无法发送 commit 消息")
        return
    message = {
        "type": "input_audio_buffer.commit",
        "event_id": generate_event_id()
    }
    future = asyncio.run_coroutine_threadsafe(ws_connection.send(json.dumps(message)), ws_loop)
    try:
        future.result()
        logging.info("发送 commit 消息")
    except Exception as e:
        logging.error("发送 commit 消息出错: %s", e)


def send_session_update():
    """
    手动触发 session.update 消息，用于清除服务端上下文（仅测试使用）
    """
    global ws_connection, ws_loop
    if ws_connection is None or ws_loop is None:
        logging.error("WebSocket 连接未就绪，无法发送 session.update 消息")
        return
    message = {
        "type": "session.update",
        "event_id": generate_event_id()
    }
    future = asyncio.run_coroutine_threadsafe(ws_connection.send(json.dumps(message)), ws_loop)
    try:
        future.result()
        logging.info("发送 session.update 消息")
    except Exception as e:
        logging.error("发送 session.update 消息出错: %s", e)


def audio_rms(frame):
    """
    计算 PCM16 数据的 RMS（均方根振幅），用于判断是否处于静音状态。
    """
    count = len(frame) // 2
    format_str = "%dh" % count
    shorts = struct.unpack(format_str, frame)
    sum_squares = sum(s * s for s in shorts)
    rms = math.sqrt(sum_squares / count)
    return rms


def record_audio():
    """
    自动录音线程（双静默阈值版）：
      - 预缓存最近 5 帧；
      - 静默 ≥ short_silence_duration：先发送音频（不 commit）；
      - 静默 ≥ long_silence_duration：再发送一次、commit，并暂停录音；
      - 等待 AI 播放完成后恢复。
    """
    p = pyaudio.PyAudio()
    stream = p.open(format=RECORD_FORMAT,
                    channels=RECORD_CHANNELS,
                    rate=RECORD_RATE,
                    input=True,
                    frames_per_buffer=FRAMES_PER_BUFFER)
    logging.info("自动录音线程启动")

    # 两阶段静默时长
    short_silence_duration = 0.5
    long_silence_duration  = 2.0
    silence_threshold      = 1200

    is_recording      = False
    accumulated_audio = bytearray()
    silence_start     = None
    pre_audio_buffer  = deque(maxlen=5)

    try:
        while not recording_event.is_set():
            # 如果正在等待 AI 播放，丢弃输入
            if recording_paused.is_set():
                stream.read(FRAMES_PER_BUFFER, exception_on_overflow=False)
                time.sleep(0.01)
                continue

            try:
                data = stream.read(FRAMES_PER_BUFFER, exception_on_overflow=False)
            except Exception as e:
                logging.error("录音异常: %s", e)
                continue

            pre_audio_buffer.append(data)
            rms = audio_rms(data)
            now = time.time()

            # 如果已经在录音，先累积
            if is_recording:
                accumulated_audio.extend(data)

            if rms > silence_threshold:
                # 声音回来，重置静默计时
                if not is_recording:
                    is_recording = True
                    # 先把预缓存放进去
                    accumulated_audio = bytearray()
                    for frm in pre_audio_buffer:
                        accumulated_audio.extend(frm)
                    logging.info("检测到声音，开始录音，包含预缓存")
                silence_start = None
            else:
                # 静默中，只有在已录音状态才处理
                if is_recording:
                    if silence_start is None:
                        silence_start = now
                    else:
                        elapsed = now - silence_start
                        # 短静默：发送但不提交
                        if short_silence_duration <= elapsed < long_silence_duration:
                            if len(accumulated_audio) > 0:
                                if len(accumulated_audio) > (5+1+1)*FRAMES_PER_BUFFER:
                                    logging.info("静默≥%.1fs，先发送音频（不 commit）", short_silence_duration)
                                    send_audio_segment(accumulated_audio)
                                else:
                                    logging.info("The audio is dropped because it is too short.")
                                # 重置缓存，但保持录音状态继续监听
                                accumulated_audio = bytearray()
                                is_recording = False

                if silence_start is not None:
                    elapsed = now - silence_start
                    # 长静默：发送并提交，然后暂停录音
                    if elapsed >= long_silence_duration:
                        logging.info("静默≥%.1fs，发送音频并 commit", long_silence_duration)
                        send_commit()
                        # 暂停录音
                        recording_paused.set()
                        is_recording = False
                        accumulated_audio = bytearray()
                        silence_start = None

    finally:
        # 退出前若还有未发音频，统一发送并 commit
        if is_recording and accumulated_audio:
            send_audio_segment(accumulated_audio)
            send_commit()
            recording_paused.set()
        stream.stop_stream()
        stream.close()
        p.terminate()
        logging.info("自动录音线程结束")



def start_recording():
    """
    自动启动录音线程：确保 WebSocket 建立后再开始录音。
    """
    global recording_thread
    while True:
        if ws_connection is None:
            logging.error("WebSocket 连接未建立，无法开始录音")
            time.sleep(1)
        else:
            break
    if recording_thread is not None and recording_thread.is_alive():
        logging.warning("录音已经在进行中")
        return
    recording_event.clear()
    recording_thread = threading.Thread(target=record_audio, daemon=True)
    recording_thread.start()
    logging.info("开始自动录音")


class AnimatedGIF(tk.Label):
    """
    利用 Pillow 实现动态图展示。
    """

    def __init__(self, master, gif_path):
        im = Image.open(gif_path)
        self.frames = [ImageTk.PhotoImage(frame.copy()) for frame in ImageSequence.Iterator(im)]
        self.delay = im.info.get('duration', 100)
        self.frame_index = 0
        super().__init__(master, image=self.frames[0])
        self.animate()

    def animate(self):
        self.frame_index = (self.frame_index + 1) % len(self.frames)
        self.config(image=self.frames[self.frame_index])
        self.after(self.delay, self.animate)


def main():
    # 启动音频播放线程
    playback_thread = threading.Thread(target=play_audio, daemon=True)
    playback_thread.start()

    # 创建会话并启动 WebSocket 客户端
    session_data = create_session()
    if session_data:
        session_id = session_data["id"]
        ws_thread = threading.Thread(target=start_websocket_client, args=(session_id,), daemon=True)
        ws_thread.start()
        time.sleep(1)
    else:
        logging.error("无法创建会话，退出程序")
        return

    # 启动自动录音
    start_recording()

    # 构建 Tkinter 前端（大窗口显示动态图和按钮）
    root = tk.Tk()
    root.title("录音控制与动态图展示")
    root.geometry("800x600")  # 设置大一点的窗口

    # 创建一个 frame 用于放置动态图
    frame = tk.Frame(root)
    frame.pack(expand=True, fill='both')

    # 显示动态图 dynamic.gif
    animated_label = AnimatedGIF(frame, 'AI_voice_dynamic_picture0.gif')
    animated_label.pack(expand=True, fill='both')

    # 添加【清空上下文】按钮
    update_button = tk.Button(root, text="清空上下文", command=send_session_update, width=20, height=2)
    update_button.pack(pady=10)

    root.mainloop()

    # UI 关闭时，通知播放线程退出
    audio_queue.put(None)


if __name__ == "__main__":
    main()


