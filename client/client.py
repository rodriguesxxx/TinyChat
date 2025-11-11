#!/usr/bin/env python3
"""
pip install -r requirements.txt
python3 client.py
"""
from __future__ import annotations

import asyncio
import sys
from typing import List, Dict, Any

import aiohttp

BASE_URL = "http://localhost:8080"
POLL_INTERVAL = 1.0  # segundos


async def conectar(session: aiohttp.ClientSession, base_url: str, session_code: str, username: str) -> str:
    url = f"{base_url}/api/sessions/{session_code}/connect"
    payload = {"username": username}
    async with session.post(url, json=payload) as resp:
        if resp.status == 404:
            raise FileNotFoundError("Sessão não encontrada")
        if resp.status != 200:
            text = await resp.text()
            raise RuntimeError(f"Erro ao conectar: {resp.status} {text}")
        data = await resp.json()
        return data.get("token")


async def enviar_mensagem(session: aiohttp.ClientSession, base_url: str, session_code: str, token: str, text: str) -> None:
    url = f"{base_url}/api/sessions/{session_code}/messages"
    headers = {"Authorization": f"Bearer {token}"}
    payload = {"text": text}
    async with session.post(url, json=payload, headers=headers) as resp:
        if resp.status == 200 or resp.status == 204:
            return
        body = await resp.text()
        raise RuntimeError(f"Falha ao enviar mensagem: {resp.status} {body}")


async def buscar_mensagens(session: aiohttp.ClientSession, base_url: str, session_code: str) -> List[Dict[str, Any]]:
    url = f"{base_url}/api/sessions/{session_code}/messages"
    async with session.get(url) as resp:
        if resp.status == 404:
            raise FileNotFoundError("Sessão não encontrada")
        if resp.status != 200:
            text = await resp.text()
            raise RuntimeError(f"Erro ao buscar mensagens: {resp.status} {text}")
        data = await resp.json()
        return data


def solicitar_input(prompt_text: str) -> str:
    # wrapper para input() que facilita testes; chamamos em executor
    return input(prompt_text)


async def loop_entrada_e_envio(aio_sess: aiohttp.ClientSession, base_url: str, session_code: str, token: str):
    loop = asyncio.get_event_loop()
    print("Digite suas mensagens. Pressione Ctrl+C para sair.")
    while True:
        try:
            text = await loop.run_in_executor(None, solicitar_input, "")
        except (EOFError, KeyboardInterrupt):
            # usuário deseja sair
            print("\nSaindo...")
            return
        text = text.strip()
        if not text:
            continue
        try:
            await enviar_mensagem(aio_sess, base_url, session_code, token, text)
        except Exception as e:
            print(f"Erro ao enviar mensagem: {e}")


async def verificar_mensagens(aio_sess: aiohttp.ClientSession, base_url: str, session_code: str, stop_event: asyncio.Event):
    """
    Verifica (poll) as mensagens e imprime somente as que ainda não foram vistas.

    Usa um índice simples (`last_index`) para rastrear quantas mensagens já foram observadas.
    Isso evita depender de timestamps que podem ser idênticos e causar duplicações.
    """
    # inicializa last_index com a quantidade atual de mensagens para evitar reimprimir histórico
    try:
        initial = await buscar_mensagens(aio_sess, base_url, session_code)
        last_index = len(initial)
    except FileNotFoundError:
        print("Sessão removida no servidor. Encerrando verificação.")
        stop_event.set()
        return
    except Exception:
        # se não conseguirmos buscar as mensagens iniciais, começar de 0 e tentar novamente no loop
        last_index = 0

    while not stop_event.is_set():
        try:
            msgs = await buscar_mensagens(aio_sess, base_url, session_code)
            # msgs é uma lista de objetos {from, text, ts}
            if msgs and len(msgs) > last_index:
                new_slice = msgs[last_index:]
                for m in new_slice:
                    try:
                        m_from = m.get("from")
                        m_text = m.get("text")
                    except Exception:
                        continue
                    print(f"[{m_from}] {m_text}")
                last_index = len(msgs)

            await asyncio.sleep(POLL_INTERVAL)
        except FileNotFoundError:
            print("Sessão removida no servidor. Encerrando verificação.")
            stop_event.set()
            return
        except Exception as e:
            # não interromper o loop de verificação por erros transitórios
            print(f"Erro ao buscar mensagens: {e}")
            await asyncio.sleep(POLL_INTERVAL)


async def principal():
    base_url = BASE_URL
    print(f"Usando API em: {base_url}")

    async with aiohttp.ClientSession() as aio_sess:
        # loop até obter token válido
        while True:
            try:
                username = input("Nome de usuário: ").strip()
                session_code = input("Código da sessão: ").strip()
            except (EOFError, KeyboardInterrupt):
                print("\nSaindo")
                return

            # heurística rápida: verificar mensagens existentes para detectar se o nome já foi usado
            try:
                msgs = await buscar_mensagens(aio_sess, base_url, session_code)
            except FileNotFoundError:
                print("Sessão não encontrada. Tente novamente.")
                continue
            except Exception as e:
                print(f"Aviso: não foi possível listar mensagens antes de conectar: {e}")
                msgs = []

            usado = False
            for m in msgs:
                if m.get("from") == username:
                    usado = True
                    break
            if usado:
                yn = input(f"O nome '{username}' já apareceu em mensagens desta sessão. Deseja tentar outro nome? (s/n): ")
                if yn.strip().lower().startswith("s"):
                    continue
                # caso contrário, prosseguir

            try:
                token = await conectar(aio_sess, base_url, session_code, username)
                if not token:
                    print("Resposta inválida do servidor (token faltando).")
                    continue
                print("Conectado com sucesso!")
                break
            except FileNotFoundError:
                print("Sessão não encontrada. Tente novamente.")
                continue
            except Exception as e:
                print(f"Erro ao conectar: {e}")
                continue

        # iniciar tarefas de verificação e entrada
        stop_event = asyncio.Event()
        poll_task = asyncio.create_task(verificar_mensagens(aio_sess, base_url, session_code, stop_event))
        send_task = asyncio.create_task(loop_entrada_e_envio(aio_sess, base_url, session_code, token))

        try:
            await send_task
        except asyncio.CancelledError:
            pass
        finally:
            stop_event.set()
            await asyncio.wait([poll_task], timeout=2.0)


if __name__ == "__main__":
    try:
        asyncio.run(principal())
    except KeyboardInterrupt:
        print("\nInterrompido pelo usuário")
